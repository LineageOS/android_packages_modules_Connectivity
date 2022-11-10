# Copyright (C) 2022 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# A collection of utilities for extracting build rule information from GN
# projects.

from __future__ import print_function
import collections
import errno
import filecmp
import json
import logging as log
import os
import re
import shutil
import subprocess
import sys

BUILDFLAGS_TARGET = '//gn:gen_buildflags'
GEN_VERSION_TARGET = '//src/base:version_gen_h'
TARGET_TOOLCHAIN = '//gn/standalone/toolchain:gcc_like_host'
HOST_TOOLCHAIN = '//gn/standalone/toolchain:gcc_like_host'
LINKER_UNIT_TYPES = ('executable', 'shared_library', 'static_library')

# TODO(primiano): investigate these, they require further componentization.
ODR_VIOLATION_IGNORE_TARGETS = {
    '//test/cts:perfetto_cts_deps',
    '//:perfetto_integrationtests',
}


def repo_root():
  """Returns an absolute path to the repository root."""
  return os.path.join(
      os.path.realpath(os.path.dirname(__file__)), os.path.pardir)


def label_to_path(label):
  """Turn a GN output label (e.g., //some_dir/file.cc) into a path."""
  assert label.startswith('//')
  return label[2:] or "./"


def label_without_toolchain(label):
  """Strips the toolchain from a GN label.

    Return a GN label (e.g //buildtools:protobuf(//gn/standalone/toolchain:
    gcc_like_host) without the parenthesised toolchain part.
    """
  return label.split('(')[0]


def label_to_target_name_with_path(label):
  """
  Turn a GN label into a target name involving the full path.
  e.g., //src/perfetto:tests -> src_perfetto_tests
  """
  name = re.sub(r'^//:?', '', label)
  name = re.sub(r'[^a-zA-Z0-9_]', '_', name)
  return name


class GnParser(object):
  """A parser with some cleverness for GN json desc files

    The main goals of this parser are:
    1) Deal with the fact that other build systems don't have an equivalent
       notion to GN's source_set. Conversely to Bazel's and Soong's filegroups,
       GN source_sets expect that dependencies, cflags and other source_set
       properties propagate up to the linker unit (static_library, executable or
       shared_library). This parser simulates the same behavior: when a
       source_set is encountered, some of its variables (cflags and such) are
       copied up to the dependent targets. This is to allow gen_xxx to create
       one filegroup for each source_set and then squash all the other flags
       onto the linker unit.
    2) Detect and special-case protobuf targets, figuring out the protoc-plugin
       being used.
    """

  class Target(object):
    """Reperesents A GN target.

        Maked properties are propagated up the dependency chain when a
        source_set dependency is encountered.
        """

    def __init__(self, name, type):
      self.name = name  # e.g. //src/ipc:ipc

      VALID_TYPES = ('static_library', 'shared_library', 'executable', 'group',
                     'action', 'source_set', 'proto_library', 'copy', 'action_foreach')
      assert (type in VALID_TYPES)
      self.type = type
      self.testonly = False
      self.toolchain = None

      # These are valid only for type == proto_library.
      # This is typically: 'proto', 'protozero', 'ipc'.
      self.proto_plugin = None
      self.proto_paths = set()
      self.proto_exports = set()
      self.proto_in_dir = ""

      self.sources = set()
      # TODO(primiano): consider whether the public section should be part of
      # bubbled-up sources.
      self.public_headers = set()  # 'public'

      # These are valid only for type == 'action'
      self.inputs = set()
      self.outputs = set()
      self.script = None
      self.args = []
      self.response_file_contents = None

      # These variables are propagated up when encountering a dependency
      # on a source_set target.
      self.cflags = set()
      self.defines = set()
      self.deps = set()
      self.libs = set()
      self.include_dirs = set()
      self.ldflags = set()
      self.source_set_deps = set()  # Transitive set of source_set deps.
      self.proto_deps = set()
      self.transitive_proto_deps = set()

      # Deps on //gn:xxx have this flag set to True. These dependencies
      # are special because they pull third_party code from buildtools/.
      # We don't want to keep recursing into //buildtools in generators,
      # this flag is used to stop the recursion and create an empty
      # placeholder target once we hit //gn:protoc or similar.
      self.is_third_party_dep_ = False

    def __lt__(self, other):
      if isinstance(other, self.__class__):
        return self.name < other.name
      raise TypeError(
          '\'<\' not supported between instances of \'%s\' and \'%s\'' %
          (type(self).__name__, type(other).__name__))

    def __repr__(self):
      return json.dumps({
          k: (list(sorted(v)) if isinstance(v, set) else v)
          for (k, v) in self.__dict__.items()
      },
                        indent=4,
                        sort_keys=True)

    def update(self, other):
      for key in ('cflags', 'defines', 'deps', 'include_dirs', 'ldflags',
                  'source_set_deps', 'proto_deps', 'transitive_proto_deps',
                  'libs', 'proto_paths'):
        self.__dict__[key].update(other.__dict__.get(key, []))

  def __init__(self, gn_desc):
    self.gn_desc_ = gn_desc
    self.all_targets = {}
    self.linker_units = {}  # Executables, shared or static libraries.
    self.source_sets = {}
    self.actions = {}
    self.proto_libs = {}
    self.java_sources = set()

  def _get_response_file_contents(self, action_desc):
    # response_file_contents are formatted as:
    # ['--flags', '--flag=true && false'] and need to be formatted as:
    # '--flags --flag=\"true && false\"'
    flags = action_desc.get('response_file_contents', [])
    formatted_flags = []
    for flag in flags:
      if '=' in flag:
        key, val = flag.split('=')
        formatted_flags.append('%s=\\"%s\\"' % (key, val))
      else:
        formatted_flags.append(flag)

    return ' '.join(formatted_flags)

  def _is_java_target(self, target):
    # Per https://chromium.googlesource.com/chromium/src/build/+/HEAD/android/docs/java_toolchain.md
    # java target names must end in "_java".
    # TODO: There are some other possible variations we might need to support.
    return target.type == 'group' and re.match('.*_java$', target.name)


  def get_target(self, gn_target_name):
    """Returns a Target object from the fully qualified GN target name.

        It bubbles up variables from source_set dependencies as described in the
        class-level comments.
        """
    target = self.all_targets.get(gn_target_name)
    if target is not None:
      return target  # Target already processed.

    desc = self.gn_desc_[gn_target_name]
    target = GnParser.Target(gn_target_name, desc['type'])
    target.testonly = desc.get('testonly', False)
    target.toolchain = desc.get('toolchain', None)
    self.all_targets[gn_target_name] = target

    # TODO: determine if below comment should apply for cronet builds in Android.
    # We should never have GN targets directly depend on buidtools. They
    # should hop via //gn:xxx, so we can give generators an opportunity to
    # override them.
    # Specifically allow targets to depend on libc++ and libunwind.
    if not any(match in gn_target_name for match in ['libc++', 'libunwind']):
      assert (not gn_target_name.startswith('//buildtools'))


    # Don't descend further into third_party targets. Genrators are supposed
    # to either ignore them or route to other externally-provided targets.
    if gn_target_name.startswith('//gn'):
      target.is_third_party_dep_ = True
      return target

    proto_target_type, proto_desc = self.get_proto_target_type(target)
    if proto_target_type is not None:
      self.proto_libs[target.name] = target
      target.type = 'proto_library'
      target.proto_plugin = proto_target_type
      target.proto_paths.update(self.get_proto_paths(proto_desc))
      target.proto_exports.update(self.get_proto_exports(proto_desc))
      target.proto_in_dir = self.get_proto_in_dir(proto_desc)
      target.sources.update(proto_desc.get('sources', []))
      assert (all(x.endswith('.proto') for x in target.sources))
    elif target.type == 'source_set':
      self.source_sets[gn_target_name] = target
      target.sources.update(desc.get('sources', []))
    elif target.type in LINKER_UNIT_TYPES:
      self.linker_units[gn_target_name] = target
      target.sources.update(desc.get('sources', []))
    elif target.type in ['action', 'action_foreach']:
      self.actions[gn_target_name] = target
      target.inputs.update(desc.get('inputs', []))
      target.sources.update(desc.get('sources', []))
      outs = [re.sub('^//out/.+?/gen/', '', x) for x in desc['outputs']]
      target.outputs.update(outs)
      target.script = desc['script']
      target.args = desc['args']
      target.response_file_contents = self._get_response_file_contents(desc)
    elif target.type == 'copy':
      # TODO: copy rules are not currently implemented.
      self.actions[gn_target_name] = target
    elif self._is_java_target(target):
      # java_group identifies the group target generated by the android_library
      # or java_library template. A java_group must not be added as a dependency, but sources are collected
      log.debug('Found java target %s', target.name)
      target.type = 'java_group'

    # Default for 'public' is //* - all headers in 'sources' are public.
    # TODO(primiano): if a 'public' section is specified (even if empty), then
    # the rest of 'sources' is considered inaccessible by gn. Consider
    # emulating that, so that generated build files don't end up with overly
    # accessible headers.
    public_headers = [x for x in desc.get('public', []) if x != '*']
    target.public_headers.update(public_headers)

    target.cflags.update(desc.get('cflags', []) + desc.get('cflags_cc', []))
    target.libs.update(desc.get('libs', []))
    target.ldflags.update(desc.get('ldflags', []))
    target.defines.update(desc.get('defines', []))
    target.include_dirs.update(desc.get('include_dirs', []))

    # Recurse in dependencies.
    for dep_name in desc.get('deps', []):
      dep = self.get_target(dep_name)
      if dep.is_third_party_dep_:
        target.deps.add(dep_name)
      elif dep.type == 'proto_library':
        target.proto_deps.add(dep_name)
        target.transitive_proto_deps.add(dep_name)
        target.proto_paths.update(dep.proto_paths)
        target.transitive_proto_deps.update(dep.transitive_proto_deps)
      elif dep.type == 'source_set':
        target.source_set_deps.add(dep_name)
        target.update(dep)  # Bubble up source set's cflags/ldflags etc.
      elif dep.type == 'group':
        target.update(dep)  # Bubble up groups's cflags/ldflags etc.
      elif dep.type in ['action', 'action_foreach', 'copy']:
        if proto_target_type is None:
          target.deps.add(dep_name)
      elif dep.type in LINKER_UNIT_TYPES:
        target.deps.add(dep_name)
      elif dep.type == 'java_group':
        # Explicitly break dependency chain when a java_group is added.
        # Java sources are collected and eventually compiled as one large
        # java_library.
        pass

      # Collect java sources. Java sources are kept inside the __compile_java target.
      # This target can be used for both host and target compilation; only add
      # the sources if they are destined for the target (i.e. they are a
      # dependency of the __dex target)
      # Note: this skips prebuilt java dependencies. These will have to be
      # added manually when building the jar.
      if re.match('.*__dex$', target.name):
        if re.match('.*__compile_java$', dep.name):
          log.debug('Adding java sources for %s', dep.name)
          java_srcs = [src for src in dep.inputs if os.path.splitext(src)[1] == '.java']
          self.java_sources.update(java_srcs)

    return target

  def get_proto_exports(self, proto_desc):
    # exports in metadata will be available for source_set targets.
    metadata = proto_desc.get('metadata', {})
    return metadata.get('exports', [])

  def get_proto_paths(self, proto_desc):
    # import_dirs in metadata will be available for source_set targets.
    metadata = proto_desc.get('metadata', {})
    return metadata.get('import_dirs', [])


  def get_proto_in_dir(self, proto_desc):
    args = proto_desc.get('args')
    return re.sub('^\.\./\.\./', '', args[args.index('--proto-in-dir') + 1])

  def get_proto_target_type(self, target):
    """ Checks if the target is a proto library and return the plugin.

        Returns:
            (None, None): if the target is not a proto library.
            (plugin, proto_desc) where |plugin| is 'proto' in the default (lite)
            case or 'protozero' or 'ipc' or 'descriptor'; |proto_desc| is the GN
            json desc of the target with the .proto sources (_gen target for
            non-descriptor types or the target itself for descriptor type).
        """
    parts = target.name.split('(', 1)
    name = parts[0]
    toolchain = '(' + parts[1] if len(parts) > 1 else ''

    # Descriptor targets don't have a _gen target; instead we look for the
    # characteristic flag in the args of the target itself.
    desc = self.gn_desc_.get(target.name)
    if '--descriptor_set_out' in desc.get('args', []):
      return 'descriptor', desc

    # Source set proto targets have a non-empty proto_library_sources in the
    # metadata of the description.
    metadata = desc.get('metadata', {})
    if 'proto_library_sources' in metadata:
      return 'source_set', desc

    # In all other cases, we want to look at the _gen target as that has the
    # important information.
    gen_desc = self.gn_desc_.get('%s_gen%s' % (name, toolchain))
    if gen_desc is None or gen_desc['type'] != 'action':
      return None, None
    if gen_desc['script'] != '//tools/protoc_wrapper/protoc_wrapper.py':
      return None, None
    plugin = 'proto'
    args = gen_desc.get('args', [])
    for arg in (arg for arg in args if arg.startswith('--plugin=')):
      # |arg| at this point looks like:
      #  --plugin=protoc-gen-plugin=gcc_like_host/protozero_plugin
      # or
      #  --plugin=protoc-gen-plugin=protozero_plugin
      plugin = arg.split('=')[-1].split('/')[-1].replace('_plugin', '')
    return plugin, gen_desc

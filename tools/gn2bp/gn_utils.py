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
LINKER_UNIT_TYPES = ('executable', 'shared_library', 'static_library', 'source_set')

# TODO(primiano): investigate these, they require further componentization.
ODR_VIOLATION_IGNORE_TARGETS = {
    '//test/cts:perfetto_cts_deps',
    '//:perfetto_integrationtests',
}
ARCH_REGEX = r'(android_x86_64|android_x86|android_arm|android_arm64|host)'
DEX_REGEX = '.*__dex__%s$' % ARCH_REGEX
COMPILE_JAVA_REGEX = '.*__compile_java__%s$' % ARCH_REGEX
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

def _is_java_source(src):
  return os.path.splitext(src)[1] == '.java' and not src.startswith("//out/test/gen/")

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
    class Arch():
      """Architecture-dependent properties
        """
      def __init__(self):
        self.sources = set()
        self.cflags = set()
        self.defines = set()
        self.include_dirs = set()
        self.deps = set()
        self.transitive_static_libs_deps = set()
        self.source_set_deps = set()


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
      self.rtti = False

      # TODO: come up with a better way to only run this once.
      # is_finalized tracks whether finalize() was called on this target.
      self.is_finalized = False
      self.arch = dict()

    def host_supported(self):
      return 'host' in self.arch

    def device_supported(self):
      return any([name.startswith('android') for name in self.arch.keys()])

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

    def update(self, other, arch):
      for key in ('cflags', 'defines', 'deps', 'include_dirs', 'ldflags',
                  'source_set_deps', 'proto_deps', 'transitive_proto_deps',
                  'libs', 'proto_paths'):
        self.__dict__[key].update(other.__dict__.get(key, []))

      for key_in_arch in ('cflags', 'defines', 'include_dirs', 'source_set_deps'):
        self.arch[arch].__dict__[key_in_arch].update(
          other.arch[arch].__dict__.get(key_in_arch, []))

    def finalize(self):
      """Move common properties out of arch-dependent subobjects to Target object.

        TODO: find a better name for this function.
        """
      if self.is_finalized:
        return
      self.is_finalized = True

      # Target contains the intersection of arch-dependent properties
      self.sources = set.intersection(*[arch.sources for arch in self.arch.values()])
      self.cflags = set.intersection(*[arch.cflags for arch in self.arch.values()])
      self.defines = set.intersection(*[arch.defines for arch in self.arch.values()])
      self.include_dirs = set.intersection(*[arch.include_dirs for arch in self.arch.values()])
      self.deps.update(set.intersection(*[arch.deps for arch in self.arch.values()]))
      self.source_set_deps.update(set.intersection(*[arch.source_set_deps for arch in self.arch.values()]))

      # Deduplicate arch-dependent properties
      for arch in self.arch.keys():
        self.arch[arch].sources -= self.sources
        self.arch[arch].cflags -= self.cflags
        self.arch[arch].defines -= self.defines
        self.arch[arch].include_dirs -= self.include_dirs
        self.arch[arch].deps -= self.deps
        self.arch[arch].source_set_deps -= self.source_set_deps


  def __init__(self):
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

  def _get_arch(self, toolchain):
    if toolchain == '//build/toolchain/android:android_clang_x86':
      return 'android_x86'
    elif toolchain == '//build/toolchain/android:android_clang_x64':
      return 'android_x86_64'
    elif toolchain == '//build/toolchain/android:android_clang_arm':
      return 'android_arm'
    elif toolchain == '//build/toolchain/android:android_clang_arm64':
      return 'android_arm64'
    else:
      return 'host'

  def get_target(self, gn_target_name):
    """Returns a Target object from the fully qualified GN target name.

      get_target() requires that parse_gn_desc() has already been called.
      """
    # Run this every time as parse_gn_desc can be called at any time.
    for target in self.all_targets.values():
      target.finalize()

    return self.all_targets[label_without_toolchain(gn_target_name)]

  def parse_gn_desc(self, gn_desc, gn_target_name):
    """Parses a gn desc tree and resolves all target dependencies.

        It bubbles up variables from source_set dependencies as described in the
        class-level comments.
        """
    # Use name without toolchain for targets to support targets built for
    # multiple archs.
    target_name = label_without_toolchain(gn_target_name)
    desc = gn_desc[gn_target_name]
    type_ = desc['type']
    arch = self._get_arch(desc['toolchain'])

    # Action modules can differ depending on the target architecture, yet
    # genrule's do not allow to overload cmd per target OS / arch.  Create a
    # separate action for every architecture.
    # Cover both action and action_foreach
    if type_.startswith('action'):
      target_name += '__' + arch

    target = self.all_targets.get(target_name)
    if target is None:
      target = GnParser.Target(target_name, type_)
      self.all_targets[target_name] = target

    if arch not in target.arch:
      target.arch[arch] = GnParser.Target.Arch()
    else:
      return target  # Target already processed.

    target.testonly = desc.get('testonly', False)

    proto_target_type, proto_desc = self.get_proto_target_type(gn_desc, gn_target_name)
    if proto_target_type is not None:
      self.proto_libs[target.name] = target
      target.type = 'proto_library'
      target.proto_plugin = proto_target_type
      target.proto_paths.update(self.get_proto_paths(proto_desc))
      target.proto_exports.update(self.get_proto_exports(proto_desc))
      target.proto_in_dir = self.get_proto_in_dir(proto_desc)
      for gn_proto_deps_name in proto_desc.get('deps', []):
        dep = self.parse_gn_desc(gn_desc, gn_proto_deps_name)
        target.deps.add(dep.name)
      target.arch[arch].sources.update(proto_desc.get('sources', []))
      assert (all(x.endswith('.proto') for x in target.arch[arch].sources))
    elif target.type == 'source_set':
      self.source_sets[gn_target_name] = target
      target.arch[arch].sources.update(desc.get('sources', []))
    elif target.type in LINKER_UNIT_TYPES:
      self.linker_units[gn_target_name] = target
      target.arch[arch].sources.update(desc.get('sources', []))
    elif target.type in ['action', 'action_foreach']:
      self.actions[gn_target_name] = target
      target.inputs.update(desc.get('inputs', []))
      target.arch[arch].sources.update(desc.get('sources', []))
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

    target.arch[arch].cflags.update(desc.get('cflags', []) + desc.get('cflags_cc', []))
    target.libs.update(desc.get('libs', []))
    target.ldflags.update(desc.get('ldflags', []))
    target.arch[arch].defines.update(desc.get('defines', []))
    target.arch[arch].include_dirs.update(desc.get('include_dirs', []))
    if "-frtti" in target.arch[arch].cflags:
      target.rtti = True

    # Recurse in dependencies.
    for gn_dep_name in desc.get('deps', []):
      dep = self.parse_gn_desc(gn_desc, gn_dep_name)
      if dep.type == 'proto_library':
        target.proto_deps.add(dep.name)
        target.transitive_proto_deps.add(dep.name)
        target.proto_paths.update(dep.proto_paths)
        target.transitive_proto_deps.update(dep.transitive_proto_deps)
      elif dep.type == 'source_set':
        target.arch[arch].source_set_deps.add(dep.name)
        target.arch[arch].source_set_deps.update(dep.arch[arch].source_set_deps)
      elif dep.type == 'group':
        target.update(dep, arch)  # Bubble up groups's cflags/ldflags etc.
      elif dep.type in ['action', 'action_foreach', 'copy']:
        if proto_target_type is None:
          target.deps.add(dep.name)
      elif dep.type in LINKER_UNIT_TYPES:
        target.arch[arch].deps.add(dep.name)
      elif dep.type == 'java_group':
        # Explicitly break dependency chain when a java_group is added.
        # Java sources are collected and eventually compiled as one large
        # java_library.
        #print(dep.name, target.deps)
        pass

      # Source set bubble up transitive source sets but can't be combined with this
      # if they are combined then source sets will bubble up static libraries
      # while we only want to have source sets bubble up only source sets.
      if dep.type == 'static_library':
        # Bubble up static_libs. Necessary, since soong does not propagate
        # static_libs up the build tree.
        target.arch[arch].transitive_static_libs_deps.add(dep.name)

      if arch in dep.arch:
        target.arch[arch].transitive_static_libs_deps.update(
            dep.arch[arch].transitive_static_libs_deps)
        target.arch[arch].deps.update(target.arch[arch].transitive_static_libs_deps)

      # Collect java sources. Java sources are kept inside the __compile_java target.
      # This target can be used for both host and target compilation; only add
      # the sources if they are destined for the target (i.e. they are a
      # dependency of the __dex target)
      # Note: this skips prebuilt java dependencies. These will have to be
      # added manually when building the jar.
      if re.match(DEX_REGEX, target.name):
        if re.match(COMPILE_JAVA_REGEX, dep.name):
          log.debug('Adding java sources for %s', dep.name)
          java_srcs = [src for src in dep.inputs if _is_java_source(src)]
          self.java_sources.update(java_srcs)
    #if target.name == "//build/config:executable_deps":
      #print(target.name, arch, target.arch[arch].source_set_deps)
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

  def get_proto_target_type(self, gn_desc, gn_target_name):
    """ Checks if the target is a proto library and return the plugin.

        Returns:
            (None, None): if the target is not a proto library.
            (plugin, proto_desc) where |plugin| is 'proto' in the default (lite)
            case or 'protozero' or 'ipc' or 'descriptor'; |proto_desc| is the GN
            json desc of the target with the .proto sources (_gen target for
            non-descriptor types or the target itself for descriptor type).
        """
    parts = gn_target_name.split('(', 1)
    name = parts[0]
    toolchain = '(' + parts[1] if len(parts) > 1 else ''

    # Descriptor targets don't have a _gen target; instead we look for the
    # characteristic flag in the args of the target itself.
    desc = gn_desc.get(gn_target_name)
    if '--descriptor_set_out' in desc.get('args', []):
      return 'descriptor', desc

    # Source set proto targets have a non-empty proto_library_sources in the
    # metadata of the description.
    metadata = desc.get('metadata', {})
    if 'proto_library_sources' in metadata:
      return 'source_set', desc

    # In all other cases, we want to look at the _gen target as that has the
    # important information.
    gen_desc = gn_desc.get('%s_gen%s' % (name, toolchain))
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

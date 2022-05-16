#
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

""" This script generates jarjar rule files to add a jarjar prefix to all classes, except those
that are API, unsupported API or otherwise excluded."""

import argparse
import io
import re
import subprocess
from xml import sax
from xml.sax.handler import ContentHandler
from zipfile import ZipFile


def parse_arguments(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '--jars', nargs='+',
        help='Path to pre-jarjar JAR. Can be followed by multiple space-separated paths.')
    parser.add_argument(
        '--prefix', required=True,
        help='Package prefix to use for jarjared classes, '
             'for example "com.android.connectivity" (does not end with a dot).')
    parser.add_argument(
        '--output', required=True, help='Path to output jarjar rules file.')
    parser.add_argument(
        '--apistubs', nargs='*', default=[],
        help='Path to API stubs jar. Classes that are API will not be jarjared. Can be followed by '
             'multiple space-separated paths.')
    parser.add_argument(
        '--unsupportedapi', nargs='*', default=[],
        help='Path to UnsupportedAppUsage hidden API .txt lists. '
             'Classes that have UnsupportedAppUsage API will not be jarjared. Can be followed by '
             'multiple space-separated paths.')
    parser.add_argument(
        '--excludes', nargs='*', default=[],
        help='Path to files listing classes that should not be jarjared. Can be followed by '
             'multiple space-separated paths. '
             'Each file should contain one full-match regex per line. Empty lines or lines '
             'starting with "#" are ignored.')
    parser.add_argument(
        '--dexdump', default='dexdump', help='Path to dexdump binary.')
    return parser.parse_args(argv)


class DumpHandler(ContentHandler):
    def __init__(self):
        super().__init__()
        self._current_package = None
        self.classes = []

    def startElement(self, name, attrs):
        if name == 'package':
            attr_name = attrs.getValue('name')
            assert attr_name != '', '<package> element missing name'
            assert self._current_package is None, f'Found nested package tags for {attr_name}'
            self._current_package = attr_name
        elif name == 'class':
            attr_name = attrs.getValue('name')
            assert attr_name != '', '<class> element missing name'
            self.classes.append(self._current_package + '.' + attr_name)

    def endElement(self, name):
        if name == 'package':
            self._current_package = None


def _list_toplevel_dex_classes(jar, dexdump):
    """List all classes in a dexed .jar file that are not inner classes."""
    # Empty jars do net get a classes.dex: return an empty set for them
    with ZipFile(jar, 'r') as zip_file:
        if not zip_file.namelist():
            return set()
    cmd = [dexdump, '-l', 'xml', '-e', jar]
    dump = subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE)
    handler = DumpHandler()
    xml_parser = sax.make_parser()
    xml_parser.setContentHandler(handler)
    xml_parser.parse(io.StringIO(dump.stdout))
    return set([_get_toplevel_class(c) for c in handler.classes])


def _list_jar_classes(jar):
    with ZipFile(jar, 'r') as zip:
        files = zip.namelist()
        assert 'classes.dex' not in files, f'Jar file {jar} is dexed, ' \
                                           'expected an intermediate zip of .class files'
        class_len = len('.class')
        return [f.replace('/', '.')[:-class_len] for f in files
                if f.endswith('.class') and not f.endswith('/package-info.class')]


def _list_hiddenapi_classes(txt_file):
    out = set()
    with open(txt_file, 'r') as f:
        for line in f:
            if not line.strip():
                continue
            assert line.startswith('L') and ';' in line, f'Class name not recognized: {line}'
            clazz = line.replace('/', '.').split(';')[0][1:]
            out.add(_get_toplevel_class(clazz))
    return out


def _get_toplevel_class(clazz):
    """Return the name of the toplevel (not an inner class) enclosing class of the given class."""
    if '$' not in clazz:
        return clazz
    return clazz.split('$')[0]


def _get_excludes(path):
    out = []
    with open(path, 'r') as f:
        for line in f:
            stripped = line.strip()
            if not stripped or stripped.startswith('#'):
                continue
            out.append(re.compile(stripped))
    return out


def make_jarjar_rules(args):
    excluded_classes = set()
    for apistubs_file in args.apistubs:
        excluded_classes.update(_list_toplevel_dex_classes(apistubs_file, args.dexdump))

    for unsupportedapi_file in args.unsupportedapi:
        excluded_classes.update(_list_hiddenapi_classes(unsupportedapi_file))

    exclude_regexes = []
    for exclude_file in args.excludes:
        exclude_regexes.extend(_get_excludes(exclude_file))

    with open(args.output, 'w') as outfile:
        for jar in args.jars:
            jar_classes = _list_jar_classes(jar)
            jar_classes.sort()
            for clazz in jar_classes:
                if (_get_toplevel_class(clazz) not in excluded_classes and
                        not any(r.fullmatch(clazz) for r in exclude_regexes)):
                    outfile.write(f'rule {clazz} {args.prefix}.@0\n')
                    # Also include jarjar rules for unit tests of the class, so the package matches
                    outfile.write(f'rule {clazz}Test {args.prefix}.@0\n')
                    outfile.write(f'rule {clazz}Test$* {args.prefix}.@0\n')


def _main():
    # Pass in None to use argv
    args = parse_arguments(None)
    make_jarjar_rules(args)


if __name__ == '__main__':
    _main()

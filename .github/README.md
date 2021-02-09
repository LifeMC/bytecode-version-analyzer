# bytecode-version-analyzer
[![Release](https://img.shields.io/github/release/LifeMC/bytecode-version-analyzer.svg)](https://github.com/LifeMC/bytecode-version-analyzer/releases)
[![Discord](https://img.shields.io/discord/231814350619410433.svg?label=discord)](https://discord.gg/tmupwqn)
[![License](https://img.shields.io/badge/license-gpl%203.0-brightgreen.svg)](https://opensource.org/licenses/GPL-3.0)
[![Build Status](https://github.com/LifeMC/bytecode-version-analyzer/workflows/Java%20CI/badge.svg)](https://github.com/LifeMC/bytecode-version-analyzer/actions)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/42c5db206def479d8b31f8430203034f)](https://app.codacy.com/app/LifeMC/bytecode-version-analyzer?utm_source=github.com&utm_medium=referral&utm_content=LifeMC/bytecode-version-analyzer&utm_campaign=Badge_Grade_Dashboard)
[![Known Vulnerabilities](https://snyk.io//test/github/LifeMC/bytecode-version-analyzer/badge.svg?targetFile=pom.xml)](https://snyk.io//test/github/LifeMC/bytecode-version-analyzer?targetFile=pom.xml)

Analyzer for bytecode versions of class files in a JAR file.

# Download

You can get the latest release from GitHub Releases, [click here](https://github.com/LifeMC/bytecode-version-analyzer/releases/latest/) for the latest stable release.

# Usage

## Downloading
Download the latest releases JAR file (via GitHub Releases), open a command line and run the JAR via java -jar, providing the necessary arguments.

If you don't provide any arguments, it will print the help text. Here is a screenshot of the help message from v0.1-SNAPSHOT:

![image](https://user-images.githubusercontent.com/24778409/107349625-7d71f580-6ad9-11eb-8856-1a87809e2d62.png)

## Showing version of a single class file
Specifying a class name as an argument will look for a class file in the current directory and print the version of it. Specifying a full path of class file is also supported.

![image](https://user-images.githubusercontent.com/24778409/107349723-9bd7f100-6ad9-11eb-811a-3f826094700b.png)

## Analyzing versions of class files in an archive
Specifying a archive (zip, jar) file name as an argument will also look for an archive file in the current directory, and will analyze all class files in it. Specifying a full path is also supported.

![image](https://user-images.githubusercontent.com/24778409/107349858-c6c24500-6ad9-11eb-9cdb-5b8df740c41f.png)

## Finding classes below or above specific version
If you don't provide --print-if-above or --print-if-below arguments, it will only print messages such as X amount of classes use 52.0 (Java 8) for every different bytecode version. Here is an image if you provide both arguments:

![image](https://user-images.githubusercontent.com/24778409/107349967-e78a9a80-6ad9-11eb-8577-602ec6ceba4c.png)

# Example Use Cases

You can find old libraries that compile with/into Java 6, or you can find for example libraries that compile into Java 11, or other versions.

# Limitations/To-do

## Notes

- Multi-Release JARs are supported. However, to fully support Multi-Release JARs, you must run the program from Java 10 or above.
  This because JarFile#versionedStream is added in Java 10.

  However, you can still get partial support on Java 9. This because basic versioned JarFile
  support is added in Java 9. (The new JarFile constructor accepting Runtime.Version objects.)

## Not tested for

- Classes compiled for preview runtimes (--enable-preview) are not tested.

## Limitations/notes

- This tool will still take into account even if a class file is not used or loaded in run-time.
- This not a tool to modify/update bytecode or class/jar files in any way.

## To-do

- [ ] Refactor code to make it more organized
- [ ] Add tests
- [ ] Test with preview (--enable-preview) versions
- [ ] Option to print only packages with below/above specified version (i.e not every single class, reduces messages)
- [ ] Use a logger to log messages/errors

# Security Policy

Please see [Security policy](https://github.com/LifeMC/bytecode-version-analyzer/blob/main/.github/SECURITY.md).

# Building, Project Preferences, Contributing

Please see [Contributing documentation](https://github.com/LifeMC/bytecode-version-analyzer/blob/main/.github/CONTRIBUTING.md) and [Project preferences](https://github.com/LifeMC/bytecode-version-analyzer/blob/main/.github/PROJECT_PREFERENCES.md).

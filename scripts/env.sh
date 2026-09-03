# Source this file before running sbt or the test harness.
# Uses Homebrew OpenJDK 25, latest LTS as of 2026.

export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

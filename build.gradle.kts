// Plugins are declared per module rather than here.
//
// The Android Gradle Plugin is only resolvable in environments that can reach Google's Maven
// repository, and :core:dsp / :core:training deliberately do not need it - keeping the root build
// script plugin-free is what lets `gradle :core:dsp:test` run on a bare JDK.

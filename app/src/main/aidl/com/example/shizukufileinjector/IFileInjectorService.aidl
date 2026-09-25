// AIDL interface exposed by the code that runs INSIDE the Shizuku shell/root
// process. Everything called through this runs with Shizuku's privilege level,
// not the app's normal sandboxed privilege.
package com.example.shizukufileinjector;

interface IFileInjectorService {

    // Copies srcPath -> destPath using the privileged process, then chmods it.
    String injectFile(String srcPath, String destPath, String chmod);

    // Runs an arbitrary shell command and returns combined stdout+stderr.
    String runShell(String command);

    // Copies the bundled "anshu-on-top" asset folder directly to the Free Fire data directory.
    String injectAssetsFolder();

    // Deletes the injected files from the Free Fire directory for Offline Mode.
    String deleteInjectedFiles();

    void destroy();
}
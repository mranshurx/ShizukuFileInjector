package com.example.shizukufileinjector;

interface IFileInjectorService {

    // Copies srcPath -> destPath using the privileged process, then chmods it.
    String injectFile(String srcPath, String destPath, String chmod);

    // Runs an arbitrary shell command and returns combined stdout+stderr.
    String runShell(String command);

    // Downloads and injects files from the remote GitHub 'anshu-on-top' folder.
    String injectAssetsFolder();

    // Deletes the injected files from the Free Fire directory for Offline Mode.
    String deleteInjectedFiles();

    void destroy();
}
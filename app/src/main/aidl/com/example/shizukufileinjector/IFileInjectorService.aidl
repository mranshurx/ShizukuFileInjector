package com.example.shizukufileinjector;

interface IFileInjectorService {
    String injectFile(String srcPath, String destPath, String chmod);
    String runShell(String command);
    String injectAssetsFolder();
    String deleteInjectedFiles();
    void destroy();
}
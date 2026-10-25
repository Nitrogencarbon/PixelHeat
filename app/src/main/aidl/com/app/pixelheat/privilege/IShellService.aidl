package com.app.pixelheat.privilege;

interface IShellService {
    // Transaction code reserved by Shizuku for user service teardown.
    void destroy() = 16777114;

    String exec(String command) = 1;

    // Returns null when the command is killed for running longer than timeoutMs.
    String execTimed(String command, long timeoutMs) = 2;
}

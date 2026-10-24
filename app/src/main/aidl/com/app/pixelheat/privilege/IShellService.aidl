package com.app.pixelheat.privilege;

interface IShellService {
    // Transaction code reserved by Shizuku for user service teardown.
    void destroy() = 16777114;

    String exec(String command) = 1;
}

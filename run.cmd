@echo off
rem ============================================================
rem  启动微信机器人（开发模式）
rem  先把控制台代码页切到 UTF-8（chcp 65001），
rem  否则 Windows 控制台（默认 GBK/936）无法正常显示中文日志。
rem  用法：run.cmd           -> mvn spring-boot:run
rem        run.cmd test      -> mvn test
rem ============================================================
chcp 65001 >nul
if "%~1"=="" (
    mvn spring-boot:run
) else (
    mvn %*
)
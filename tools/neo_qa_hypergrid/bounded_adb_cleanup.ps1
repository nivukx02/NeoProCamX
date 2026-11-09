# Cleanup must never replace the recording failure with an ADB disconnect error, nor wait forever
# in logcat when USB disappears. Only the child process created here may be terminated.
function Invoke-QABoundedAdbCleanup {
    param(
        [Parameter(Mandatory = $true)][string]$AdbPath,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [ValidateRange(100, 30000)][int]$TimeoutMs = 10000
    )
    $process = New-Object System.Diagnostics.Process
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $AdbPath
    $startInfo.Arguments = ((@('-s', $Serial) + $Arguments) | ForEach-Object {
        '"' + ([string]$_).Replace('"', '\"') + '"'
    }) -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process.StartInfo = $startInfo
    $result = [ordered]@{ success = $false; timed_out = $false; exit_code = -1; output = ''; error = '' }
    try {
        if (-not $process.Start()) { throw 'Cannot start cleanup ADB child' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMs)) {
            $result.timed_out = $true
            $process.Kill()
            [void]$process.WaitForExit(2000)
            $result.error = "ADB cleanup timed out after ${TimeoutMs}ms"
        } else {
            $result.exit_code = $process.ExitCode
            $result.success = $process.ExitCode -eq 0
        }
        if ($stdout.IsCompleted) { $result.output = $stdout.GetAwaiter().GetResult() }
        if ($stderr.IsCompleted) { $result.error += $stderr.GetAwaiter().GetResult() }
    } catch {
        $result.error += $_.Exception.Message
    } finally {
        $process.Dispose()
    }
    return [pscustomobject]$result
}

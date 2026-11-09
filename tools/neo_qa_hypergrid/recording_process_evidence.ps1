# Pure evidence selection. Keep the original logs separately; only this process's records may
# satisfy (or fail) a recording gate. Shared persistent logs can contain several app generations.
function Select-QARecordingProcessEvidence {
    param(
        [AllowNull()][AllowEmptyString()][string]$Text,
        [AllowNull()][AllowEmptyString()][string]$ProcessId
    )
    if ([string]::IsNullOrWhiteSpace($ProcessId)) { return '' }
    if ($ProcessId -notmatch '\A[1-9][0-9]*\z') {
        throw 'Recording evidence requires exactly one positive process ID'
    }
    if ([string]::IsNullOrWhiteSpace($Text)) { return '' }
    $escapedPid = [regex]::Escape($ProcessId)
    $threadtime = '^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+' +
        $escapedPid + '\s+\d+\s+[VDIWEF]\s+'
    $persistent = '^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+' +
        '[VDIWEF]/[^\s]+\s+\[[^\r\n]*\(pid:' + $escapedPid + '\)\]'
    return (($Text -split '\r?\n') | Where-Object {
        $_ -match $threadtime -or $_ -match $persistent
    }) -join "`n"
}

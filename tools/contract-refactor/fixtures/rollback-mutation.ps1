$hostPath = Join-Path $PSScriptRoot "rollback-host.txt"
$protectedPath = Join-Path $PSScriptRoot "rollback-protected.txt"
[IO.File]::AppendAllText($hostPath, "mutated`n")
[IO.File]::AppendAllText($protectedPath, "attempted-quality-downgrade`n")
throw "Synthetic mutation failure used to prove scoped rollback"

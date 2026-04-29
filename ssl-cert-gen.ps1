#!/usr/bin/env pwsh

$outDir = Join-Path $PSScriptRoot ".certs"
$passFile = Join-Path $outDir "pass"

New-Item -Path $outDir -ItemType Directory -Force | Out-Null

# Ensure the password file exists before proceeding
if (-not (Test-Path $passFile)) {
    Write-Error "Password file not found at: $passFile. Please create it first."
    exit 1
}

# Read the file and trim all leading/trailing whitespace (including newlines)
$rawPass = Get-Content -Path $passFile -Raw
$env:OPENSSL_PASS = $rawPass.Trim()

# Ensure the trimmed password meets OpenSSL's 4-character minimum
if ($env:OPENSSL_PASS.Length -lt 4) {
    Write-Error "Password must be at least 4 characters long (excluding surrounding spaces)."
    exit 1
}

# Generate encrypted key using the trimmed password from the environment variable
& "C:\Program Files\Git\usr\bin\openssl.exe" genpkey -aes-256-cbc -algorithm RSA -out "${outDir}\private_encrypted.pem" -pkeyopt rsa_keygen_bits:4096 -pass "env:OPENSSL_PASS"

# Decrypt to standard key using the same environment variable
& "C:\Program Files\Git\usr\bin\openssl.exe" rsa -in "${outDir}\private_encrypted.pem" -out "${outDir}\private.pem" -passin "env:OPENSSL_PASS"

# Generate the certificate chain
& "C:\Program Files\Git\usr\bin\openssl.exe" req -key "${outDir}\private.pem" -new -x509 -days 365 -out "${outDir}\chain.crt"

# Generate base64 versions for environments that need them (e.g. CI/CD secrets)
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $outDir "chain.crt"))) | Set-Content -Path (Join-Path $outDir "chain_base64") -NoNewline
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $outDir "private_encrypted.pem"))) | Set-Content -Path (Join-Path $outDir "private_encrypted_base64") -NoNewline

# Clean up the environment variable so it doesn't linger
Remove-Item Env:\OPENSSL_PASS

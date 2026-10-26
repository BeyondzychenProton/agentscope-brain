param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$ArchivePath,
    [Parameter(Mandatory = $true)][string]$OfficialChecksumPath
)

$ErrorActionPreference = 'Stop'
$jdkRoot = (Resolve-Path -LiteralPath $JdkHome).Path.TrimEnd('\') + '\'
$archive = (Resolve-Path -LiteralPath $ArchivePath).Path
$checksumText = Get-Content -LiteralPath $OfficialChecksumPath -Raw
$checksumMatch = [regex]::Match($checksumText, '\b[a-fA-F0-9]{64}\b')
if (-not $checksumMatch.Success) { throw 'No SHA-256 in official checksum text' }
$expected = $checksumMatch.Value.ToLowerInvariant()
$actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'Official JDK archive checksum mismatch' }

$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    $comparisons = foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        $relative = $entry.FullName.Substring($entry.FullName.IndexOf('/') + 1)
        $localPath = [IO.Path]::GetFullPath((Join-Path $jdkRoot $relative))
        if (-not $localPath.StartsWith($jdkRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Archive path escapes the selected JDK'
        }
        $stream = $entry.Open()
        try {
            $archiveHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()
        } finally { $stream.Dispose() }
        $localHash = if (Test-Path -LiteralPath $localPath -PathType Leaf) {
            (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
        } else { $null }
        [ordered]@{file=$relative;archiveSha256=$archiveHash;installedSha256=$localHash;equal=($archiveHash -eq $localHash)}
    }
} finally { $zip.Dispose() }
$comparisons | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'installed-jdk-files.json') -Encoding utf8
$mismatches = @($comparisons | Where-Object { -not $_.equal })
$installedFiles = Get-ChildItem -LiteralPath $jdkRoot -Recurse -File
$unexpected = @($installedFiles | Where-Object {
    [IO.Path]::GetRelativePath($jdkRoot, $_.FullName).Replace('\','/') -notin @($comparisons.file)
} | ForEach-Object { [IO.Path]::GetRelativePath($jdkRoot, $_.FullName) })
$result = [ordered]@{
    capturedAt=[DateTimeOffset]::Now.ToString('o')
    selectedJdk='Oracle JDK 17.0.9+11-LTS-201, Windows x64'
    authorization='User accepted existing JDK 17 and authorized updating Issue 24'
    archiveUrl='https://download.oracle.com/java/17/archive/jdk-17.0.9_windows-x64_bin.zip'
    checksumUrl='https://download.oracle.com/java/17/archive/jdk-17.0.9_windows-x64_bin.zip.sha256'
    archiveLength=(Get-Item -LiteralPath $archive).Length
    officialSha256=$expected
    actualArchiveSha256=$actual
    installedJdk=$jdkRoot
    comparedFiles=@($comparisons).Count
    mismatchedFiles=$mismatches.Count
    unexpectedLocalFiles=$unexpected
    allFilesMatch=($mismatches.Count -eq 0 -and $unexpected.Count -eq 0)
}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'jdk-artifact-verification.json') -Encoding utf8
Copy-Item -LiteralPath $OfficialChecksumPath -Destination (Join-Path $PSScriptRoot 'official-jdk-sha256.txt') -Force
$result | ConvertTo-Json -Depth 5
if (-not $result.allFilesMatch) { throw 'Installed JDK differs from the verified official archive' }

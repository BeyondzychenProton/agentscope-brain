param()

$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = Join-Path $backendRoot 'src/main/java/com/agentscope/brain'
$testRoot = Join-Path $backendRoot 'src/test/java/com/agentscope/brain'
$modules = @('identity','release','conversation','media','interaction','operation','runtime','projection','memory')
$failures = [Collections.Generic.List[string]]::new()

foreach ($module in $modules) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot "$module/package-info.java"))) {
        $failures.Add("Missing module responsibility: $module/package-info.java")
    }
}
foreach ($directory in Get-ChildItem -LiteralPath $sourceRoot -Directory) {
    if ($directory.Name -notin $modules) { $failures.Add("Unexpected production module: $($directory.Name)") }
}
foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -File) {
    if ($file.Name -ne 'AgentScopeBrainApplication.java') { $failures.Add("Unexpected root source: $($file.Name)") }
}

foreach ($root in @($sourceRoot, $testRoot)) {
    foreach ($file in Get-ChildItem -LiteralPath $root -Filter '*.java' -File -Recurse) {
        $relativePath = [IO.Path]::GetRelativePath($root, $file.FullName).Replace('\','/')
        $text = [IO.File]::ReadAllText($file.FullName)
        $declaredPackage = [regex]::Match($text, '(?m)^package\s+([\w.]+)\s*;').Groups[1].Value
        $parentPath = [IO.Path]::GetDirectoryName($relativePath).Replace('\','/').Replace('/','.')
        $expectedPackage = 'com.agentscope.brain' + $(if ($parentPath) { '.' + $parentPath } else { '' })
        if ($declaredPackage -ne $expectedPackage) { $failures.Add("Package/path mismatch: $relativePath") }
        if ($root -eq $sourceRoot -and $file.Name -match '(Probe|Test|Fixture)\.java$') {
            $failures.Add("Test-only source in production: $relativePath")
        }
        if ($root -ne $sourceRoot) { continue }
        $sourceModule = ($relativePath -split '/')[0]
        foreach ($reference in [regex]::Matches($text, '(?m)^import\s+(?:static\s+)?com\.agentscope\.brain\.(\w+)\.([^;]+);')) {
            $targetModule = $reference.Groups[1].Value
            $targetPath = $reference.Groups[2].Value
            if ($targetModule -ne $sourceModule -and $targetModule -in $modules -and $targetPath -notmatch '^api\.') {
                $failures.Add("Cross-module implementation import in $relativePath -> $targetModule.$targetPath")
            }
        }
    }
}
if (Get-ChildItem -LiteralPath $backendRoot -Filter 'pom.xml' -File -Recurse | Where-Object { $_.DirectoryName -ne $backendRoot -and $_.FullName -notlike '*\target\*' }) {
    $failures.Add('Nested Maven module in formal backend')
}
$forbiddenResources = @(Get-ChildItem -LiteralPath (Join-Path $backendRoot 'src/main/resources') -File -Recurse |
    Where-Object { $_.Name -match '^(resource\.txt|application\.(yaml|yml)|application-shared\.(yaml|yml)|\.env.*)$' })
foreach ($file in $forbiddenResources) { $failures.Add("Private file in packaged resources: $($file.Name)") }

if ($failures.Count -gt 0) { throw ($failures -join [Environment]::NewLine) }
Write-Output 'Backend layout, package paths, module imports and resource placement: PASS'
Write-Output 'Import checks do not detect reflection, fully qualified calls or transaction correctness; review those in each change.'

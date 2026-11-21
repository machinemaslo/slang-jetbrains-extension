param(
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $SolutionFile,
    [Parameter(Mandatory)] [string] $ProjectFile,
    [Parameter(Mandatory)] [string] $ProjectIdentity,
    [Parameter(Mandatory)] [string] $Configuration,
    [Parameter(Mandatory)] [string] $Platform,
    [Parameter(Mandatory)] [string] $IntDir,
    [Parameter(Mandatory)] [string] $GenerationTarget,
    [Parameter(Mandatory)] [string] $MSBuildDirectory
)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Get-TextHash([string] $text) {
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($hasher.ComputeHash($utf8.GetBytes($text))).Replace('-', '').ToLowerInvariant()
    } finally {
        $hasher.Dispose()
    }
}

$workspace = [IO.Path]::GetFullPath($Workspace)
$directory = [IO.Path]::GetFullPath($IntDir).TrimEnd([char[]]@('\', '/'))
$variant = Get-TextHash "msbuild`n$ProjectIdentity`n$Configuration`n$Platform"
$pack = Join-Path $directory ".jb-slanglsp\reflection\v1\$variant.pack"
if (!(Test-Path -LiteralPath $pack -PathType Leaf)) {
    throw "The MSBuild variant did not publish its reflection pack: $pack"
}

$solutionKey = Get-TextHash ([IO.Path]::GetFileName($SolutionFile))
$associationRoot = Join-Path $workspace ".jb-slanglsp\reflection\msbuild\v1\$solutionKey"
[void] [IO.Directory]::CreateDirectory($associationRoot)
$association = Join-Path $associationRoot "$variant.assoc"
$solutionHash = (Get-FileHash -LiteralPath $SolutionFile -Algorithm SHA256).Hash.ToLowerInvariant()
$projectHash = (Get-FileHash -LiteralPath $ProjectFile -Algorithm SHA256).Hash.ToLowerInvariant()
$v1 = "JB-SLANGLSP-MSBUILD-1`n$solutionHash`n$projectHash`n"
$v2 = "JB-SLANGLSP-MSBUILD-2`n$solutionHash`n$projectHash`n"
if ($GenerationTarget -cnotmatch '\Ajb_slanglsp_reflection_[A-Za-z0-9_]{1,96}\z') {
    throw "Not a dedicated reflection target: $GenerationTarget"
}
$engine = [IO.Path]::GetFullPath((Join-Path $MSBuildDirectory 'MSBuild.exe'))
$contents = if (Test-Path -LiteralPath $engine -PathType Leaf) {
    "$v2$directory`n$GenerationTarget`n$engine`n"
} else {
    "$v1$directory`n"
}
$conflicting = $false
if ([IO.File]::Exists($association)) {
    $current = [IO.File]::ReadAllText($association, $utf8)
    if ($current -ceq $contents) {
        exit 0
    }
    if ($current.StartsWith($v2, [StringComparison]::Ordinal) -or
        ($current.StartsWith($v1, [StringComparison]::Ordinal) -and $current -cne "$v1$directory`n")) {
        # Same solution and project revision, but different evaluated output or producer.
        # Do not silently switch the IDE to the latest build.
        $conflicting = $true
        $contents = "${v1}CONFLICT`n"
    }
}

$temporary = "$association.$([Guid]::NewGuid().ToString('N')).tmp"
try {
    [IO.File]::WriteAllText($temporary, $contents, $utf8)
    if ([IO.File]::Exists($association)) {
        [IO.File]::Replace($temporary, $association, [NullString]::Value)
    } else {
        [IO.File]::Move($temporary, $association)
    }
} finally {
    if ([IO.File]::Exists($temporary)) {
        [IO.File]::Delete($temporary)
    }
}
if ($conflicting) {
    throw "Conflicting reflection producers or directories for $ProjectIdentity ($Configuration|$Platform). Remove $association before building the intended variant again."
}

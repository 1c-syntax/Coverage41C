$ErrorActionPreference = 'Stop'; # stop on all errors
$toolsDir   = "$(Split-Path -parent $MyInvocation.MyCommand.Definition)"
$url = "https://github.com/proDOOMman/Coverage41C/releases/download/v$($env:chocolateyPackageVersion)/$($env:ChocolateyPackageName)-$($env:chocolateyPackageVersion).zip"

$checksum = "FAA275EE03A79337D9FA12FF5F1FCF6A2D52E09C8C708EA5AEC6C8C8FCFC1436"

$packageArgs = @{
  packageName   = $env:ChocolateyPackageName
  unzipLocation = $toolsDir
  url           = $url
  checksumType  = "sha256"
  checksum      = $checksum
}

$covBin = Join-Path $toolsDir "\$($env:ChocolateyPackageName)-$($env:chocolateyPackageVersion)\bin"

Install-ChocolateyZipPackage @packageArgs
Install-ChocolateyPath -PathToInstall $covBin -PathType 'Machine'
Write-Host "usage: $($env:ChocolateyPackageName) --help, after restart shell"

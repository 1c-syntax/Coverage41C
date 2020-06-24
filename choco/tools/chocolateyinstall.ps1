$ErrorActionPreference = 'Stop'; # stop on all errors
$toolsDir   = "$(Split-Path -parent $MyInvocation.MyCommand.Definition)"
$url = "https://github.com/proDOOMman/Coverage41C/releases/download/v$($env:chocolateyPackageVersion)/$($env:ChocolateyPackageName)-$($env:chocolateyPackageVersion).zip"

$checksum = "0A3D91E605E99B6462DD1B67501BD4AE2A06F27371ADB245B38B8CAFD1E7DB34"

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

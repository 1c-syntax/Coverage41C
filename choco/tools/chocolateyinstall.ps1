$ErrorActionPreference = 'Stop'; # stop on all errors
$toolsDir   = "$(Split-Path -parent $MyInvocation.MyCommand.Definition)"
$url = "https://github.com/proDOOMman/Coverage41C/releases/download/v$($env:chocolateyPackageVersion)/$($env:ChocolateyPackageName)-$($env:chocolateyPackageVersion).zip"

$packageArgs = @{
  packageName   = $env:ChocolateyPackageName
  unzipLocation = $toolsDir
  url           = $url
}

$covBin = Join-Path $toolsDir "\$($env:ChocolateyPackageName)-$($env:chocolateyPackageVersion)\bin"

Install-ChocolateyZipPackage @packageArgs
Install-ChocolateyPath -PathToInstall $covBin -PathType 'Machine'
Write-Host "usage: $($env:ChocolateyPackageName) --help, after restart shell"

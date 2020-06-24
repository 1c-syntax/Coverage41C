$ErrorActionPreference = 'Stop';

$packageArgs = @{
  packageName   = $env:ChocolateyPackageName
  softwareName  = 'coverage41c*'
}

$toolsDir   = "$(Split-Path -parent $MyInvocation.MyCommand.Definition)"
$PathToRemove = Join-Path $toolsDir "\$($env:ChocolateyPackageName)-$($env:chocolateyPackageVersion)\bin"
foreach ($path in [Environment]::GetEnvironmentVariable("PATH","Machine").split(';'))
{
  If ($Path)
  {
    If (($path -ine "$PathToRemove") -AND ($path -ine "$PathToRemove\"))
    {
      [string[]]$Newpath += "$path"
    }
  }
}
$AssembledNewPath = ($newpath -join(';')).trimend(';')

[Environment]::SetEnvironmentVariable("PATH",$AssembledNewPath,"Machine")

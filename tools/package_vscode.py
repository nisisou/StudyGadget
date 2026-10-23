"""Build an offline-installable VSIX without npm dependencies."""
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
import json
ROOT=Path(__file__).resolve().parents[1]
source=ROOT/'tools/vscode-extension'
p=json.loads((source/'package.json').read_text(encoding='utf-8-sig'))
output=ROOT/'release'/f"{p['name']}-{p['version']}.vsix"
manifest=f'''<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">
<Metadata><Identity Language="en-US" Id="{p['name']}" Version="{p['version']}" Publisher="{p['publisher']}"/><DisplayName>{p['displayName']}</DisplayName><Description xml:space="preserve">{p['description']}</Description><Tags>3ds,preview</Tags><Categories>Other</Categories><GalleryFlags>Public</GalleryFlags><Properties><Property Id="Microsoft.VisualStudio.Code.Engine" Value="{p['engines']['vscode']}"/><Property Id="Microsoft.VisualStudio.Code.ExtensionDependencies" Value=""/><Property Id="Microsoft.VisualStudio.Code.ExtensionPack" Value=""/><Property Id="Microsoft.VisualStudio.Code.LocalizedLanguages" Value=""/><Property Id="Microsoft.VisualStudio.Code.PreRelease" Value="false"/></Properties></Metadata>
<Installation><InstallationTarget Id="Microsoft.VisualStudio.Code"/></Installation>
<Dependencies/><Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true"/></Assets>
</PackageManifest>'''
types='''<?xml version="1.0" encoding="utf-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="json" ContentType="application/json"/><Default Extension="js" ContentType="application/javascript"/><Default Extension="md" ContentType="text/markdown"/><Default Extension="vsixmanifest" ContentType="text/xml"/></Types>'''
output.parent.mkdir(exist_ok=True)
with ZipFile(output,'w',ZIP_DEFLATED) as z:
    z.writestr('extension.vsixmanifest',manifest)
    z.writestr('[Content_Types].xml',types)
    for file in source.iterdir():
        if file.is_file():z.writestr('extension/'+file.name,file.read_bytes().removeprefix(b'\xef\xbb\xbf'))
print(output)


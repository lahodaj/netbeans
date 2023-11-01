(cd ../..;
for m in apisupport/apisupport.ant apisupport/apisupport.project apisupport/apisupport.refactoring apisupport/apisupport.wizards ide/xml.tax java/ant.browsetask java/debugger.jpda.ant ide/extbrowser */xml */libs.xerces */xml.axi */xml.schema.model */xml.retriever; do (cd $m; ant -Dcluster.dir=vsapisupport netbeans;); done &&  rm -rf apisupport/vscode/nbcode/vsapisupport/; cp -r nbbuild/netbeans/vsapisupport/ apisupport/vscode/nbcode/
)
npm i
echo "TBD" >LICENSE
npm i @vscode/vsce@2.19.0
./node_modules/@vscode/vsce/vsce package --out apache-netbeans-apisupport-0.1.0.vsix

package org.netbeans.modules.nbcode.integration;

import org.netbeans.modules.java.lsp.server.DocumentContentSinkImpl;
import org.netbeans.spi.lsp.modification.DocumentContentModifier;
import org.netbeans.spi.lsp.modification.DocumentSave;
import org.openide.util.lookup.ServiceProvider;
import org.openide.util.lookup.ServiceProviders;

@ServiceProviders({
    @ServiceProvider(service=DocumentContentModifier.class, position=10_000),
    @ServiceProvider(service=DocumentSave.class, position=10_000)
})
public class LspDocumentContentSinkImpl extends DocumentContentSinkImpl {
    
}

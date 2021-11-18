// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.intellij.schema.psi;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import ai.vespa.intellij.schema.SdFileType;
import ai.vespa.intellij.schema.SdLanguage;
import org.jetbrains.annotations.NotNull;

/**
 * An SD file.
 *
 * @author Shahar Ariel
 */
public class SdFile extends PsiFileBase {
    
    public SdFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, SdLanguage.INSTANCE);
    }
    
    @NotNull
    @Override
    public FileType getFileType() {
        return SdFileType.INSTANCE;
    }
    
    @Override
    public String toString() {
        return "Sd File";
    }

}

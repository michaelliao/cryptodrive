package org.puppylab.cryptodrive.core.node;

import java.util.List;

public class CryptoDir extends CryptoNode {

    public List<CryptoDir>  dirs  = null; // sub dirs
    public List<CryptoFile> files = null; // files

    public boolean isRoot() {
        return this.parent == null;
    }
}

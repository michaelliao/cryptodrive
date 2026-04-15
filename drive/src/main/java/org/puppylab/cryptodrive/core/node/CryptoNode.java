package org.puppylab.cryptodrive.core.node;

import java.nio.charset.StandardCharsets;

import org.puppylab.cryptodrive.core.VaultContext;
import org.puppylab.cryptodrive.util.Base64Utils;
import org.puppylab.cryptodrive.util.EncryptUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

public abstract class CryptoNode {

    public long       inode;  // unique inode
    public CryptoNode parent; // parent node

    public long createdAt;
    public long updatedAt;
    public long accessedAt;

    private String name;             // plain file name
    private String encryptedNameB64; // encrypted file name

    public String getEncryptedNameB64() {
        return encryptedNameB64;
    }

    public void setEncryptedNameB64(String encryptedNameB64) {
        byte[] edata = Base64Utils.b64(encryptedNameB64);
        byte[] pdata = EncryptUtils.decrypt(edata, VaultContext.getCurrentKey());
        this.name = new String(pdata, StandardCharsets.UTF_8);
        this.encryptedNameB64 = encryptedNameB64;
    }

    @JsonIgnore
    public String getName() {
        return this.name;
    }

    @JsonIgnore
    public void setName(String name) {
        byte[] edata = EncryptUtils.encrypt(name.getBytes(StandardCharsets.UTF_8), VaultContext.getCurrentKey());
        this.encryptedNameB64 = Base64Utils.b64(edata);
        this.name = name;
    }
}

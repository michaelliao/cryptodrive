package org.puppylab.cryptodrive.core.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.puppylab.cryptodrive.util.Base64Utils;
import org.puppylab.cryptodrive.util.EncryptUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

public abstract class CryptoNode {

    @JsonIgnore
    public CryptoDir parent;

    private String name;             // plain file name
    private String encryptedNameB64; // encrypted file name

    public String getEncryptedNameB64() {
        return encryptedNameB64;
    }

    public void setEncryptedNameB64(String encryptedNameB64) {
        byte[] edata = Base64Utils.b64(encryptedNameB64);
        byte[] pdata = EncryptUtils.decrypt(edata, CryptoFsContext.getCurrentKey());
        this.name = new String(pdata, StandardCharsets.UTF_8);
        this.encryptedNameB64 = encryptedNameB64;
    }

    @JsonIgnore
    public String getName() {
        return this.name;
    }

    @JsonIgnore
    public void setName(String name) {
        byte[] edata = EncryptUtils.encrypt(name.getBytes(StandardCharsets.UTF_8), CryptoFsContext.getCurrentKey());
        this.encryptedNameB64 = Base64Utils.b64(edata);
        this.name = name;
    }

    /**
     * Validate() is called after load tree from json and do following work:
     * 
     * Check file inode valid.
     * 
     * Set dir inode automatically.
     * 
     * Set parent so each node can access its parent.
     */
    public abstract void validate(Map<Integer, Path> foundFiles);

    public abstract void print(int indent);
}

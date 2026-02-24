package com.lapissea.dfs.io.compress;

import java.io.IOException;

public interface Packer{
	byte[] pack(byte[] data);
	byte[] unpack(byte[] packedData) throws IOException;
	String name();
}

package com.lapissea.cfs.io;

public interface ContentBuff{
	final class _provider{
		private _provider(){}
		
		private static final ThreadLocal<byte[]> BUFFERS=ThreadLocal.withInitial(()->new byte[8]);
		
	}
	
	default byte[] contentBuf(){
		return _provider.BUFFERS.get();
	}
}

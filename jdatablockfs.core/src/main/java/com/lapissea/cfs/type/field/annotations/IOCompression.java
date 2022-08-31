package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.io.compress.GzipPacker;
import com.lapissea.cfs.io.compress.Lz4Packer;
import com.lapissea.cfs.io.compress.Packer;
import com.lapissea.cfs.io.compress.RlePacker;

import java.util.function.Supplier;

public @interface IOCompression{
	enum Type{
		/**
		 * (modified) Run length encoding: Very simple and very fast. Works great for data with low noise/variation. (eg: 20 values in a row are the same)<br/>
		 * In addition a raw block and a packed length block have been added to reduce fragility and increase compression ratio
		 */
		RLE(RlePacker::new),
		/**
		 * LZ4 compression is a balance between RLE and GZIP where RLE is very fast and not very good and GZIP is slow and highly compressible.
		 */
		LZ4(Lz4Packer::new),
		/**
		 * Your standard gzip compression. Not comparably fast but has the best compression ratio.
		 */
		GZIP(GzipPacker::new);
		
		private Supplier<Packer> src;
		private Packer           packer;
		
		Type(Supplier<Packer> src){
			this.src=src;
		}
		
		private Packer getPacker(){
			if(packer==null) gen();
			return packer;
		}
		private void gen(){
			synchronized(this){
				if(packer!=null) return;
				packer=src.get();
				src=null;
			}
		}
		public byte[] pack(byte[] data)        {return getPacker().pack(data);}
		public byte[] unpack(byte[] packedData){return getPacker().unpack(packedData);}
	}
	
	Type value() default Type.LZ4;
	
}

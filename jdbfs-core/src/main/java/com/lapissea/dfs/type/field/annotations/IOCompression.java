package com.lapissea.dfs.type.field.annotations;

import com.lapissea.dfs.io.compress.GzipPacker;
import com.lapissea.dfs.io.compress.Packer;
import com.lapissea.dfs.io.compress.RlePacker;
import com.lapissea.dfs.logging.Log;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Supplier;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IOCompression{
	enum Type{
		/**
		 * (Modified) Run length encoding: Very simple and very fast. Works great for data with low noise/variation. (Eg: 20 values in a row are the same)<br/>
		 * In addition, a raw block and a packed length block have been added to reduce fragility and increase compression ratio at a small sacrifice in speed.
		 */
		RLE(RlePacker::new),
		/**
		 * LZ4 fast compression is a very fast compression algorithm similar in speed to RLE but works better on higher noise/variation data.
		 */
		LZ4_FAST(() -> {
			return getByName("LZ4-Fast")
				       .or(getByNameCName("lz4.Lz4Packer$Fast"))
				       .orElseThrow(() -> new UnsupportedOperationException(
					       "LZ4 compression not supported, please include an LZ4-Fast Packer implementation (jdbfs-lz4)"
				       ));
		}),
		/**
		 * LZ4 compression is a balance between RLE and GZIP. It is significantly slower than RLE but is also produces a higher compression
		 * ratio. This is probably the right choice if the type of data is unknown.
		 */
		LZ4(() -> {
			return getByName("LZ4-High")
				       .or(getByNameCName("lz4.Lz4Packer$High"))
				       .orElseThrow(() -> new UnsupportedOperationException(
					       "LZ4 compression not supported, please include an LZ4-High Packer implementation (jdbfs-lz4)"
				       ));
		}),
		/**
		 * Your standard gzip compression. Pretty slow but has the best compression ratio.
		 */
		GZIP(GzipPacker::new);
		
		private static Supplier<Optional<Packer>> getByNameCName(String name){
			return () -> {
				var cName = Packer.class.getPackageName() + "." + name;
				try{
					var clazz = Class.forName(cName, false, Packer.class.getClassLoader()).asSubclass(Packer.class);
					var inst  = clazz.getConstructor().newInstance();
					Log.info("Loaded {}#yellow without ServiceLoader", cName);
					return Optional.of(inst);
				}catch(Throwable e){
					Log.warn("Failed to load {}#red\n  {}", cName, e);
					return Optional.empty();
				}
			};
		}
		private static Optional<Packer> getByName(String algorithm){
			for(Packer packer : ServiceLoader.load(Packer.class)){
				if(packer.name().equals(algorithm)){
					return Optional.of(packer);
				}
			}
			return Optional.empty();
		}
		
		private Supplier<Packer> src;
		private Packer           packer;
		
		Type(Supplier<Packer> src){
			this.src = src;
		}
		
		private Packer getPacker(){
			if(packer == null) gen();
			return packer;
		}
		private void gen(){
			synchronized(this){
				if(packer != null) return;
				packer = src.get();
				src = null;
			}
		}
		public byte[] pack(byte[] data)                           { return getPacker().pack(data); }
		public byte[] unpack(byte[] packedData) throws IOException{ return getPacker().unpack(packedData); }
	}
	
	Type value() default Type.LZ4;
}

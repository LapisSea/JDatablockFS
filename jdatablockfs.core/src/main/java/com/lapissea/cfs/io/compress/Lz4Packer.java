package com.lapissea.cfs.io.compress;

import com.lapissea.cfs.GlobalConfig;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.util.Arrays;
import java.util.function.Supplier;

import static com.lapissea.cfs.io.compress.Packer.readSiz;
import static com.lapissea.cfs.io.compress.Packer.sizeBytes;
import static com.lapissea.cfs.io.compress.Packer.writeSiz;

public abstract sealed class Lz4Packer implements Packer{
	
	private enum FactoryProvider{
		ANY(LZ4Factory::fastestInstance),
		JAVA_ONLY(LZ4Factory::fastestJavaInstance),
		SAFE_ONLY(LZ4Factory::safeInstance);
		
		private final Supplier<LZ4Factory> factorySupplier;
		
		FactoryProvider(Supplier<LZ4Factory> factorySupplier){
			this.factorySupplier = factorySupplier;
		}
		
		private LZ4Factory get(){
			return factorySupplier.get();
		}
	}
	
	private static final FactoryProvider FACTORY_PROVIDER = GlobalConfig.configEnum("lz4.compatibility", FactoryProvider.ANY);
	
	public static final class High extends Lz4Packer{
		
		@Override
		LZ4Compressor getCompressor(){
			class Compressor{
				private static final LZ4Compressor INST = FACTORY_PROVIDER.get().highCompressor();
			}
			return Compressor.INST;
		}
	}
	
	public static final class Fast extends Lz4Packer{
		
		@Override
		LZ4Compressor getCompressor(){
			class Compressor{
				private static final LZ4Compressor INST = FACTORY_PROVIDER.get().fastCompressor();
			}
			return Compressor.INST;
		}
	}
	
	abstract LZ4Compressor getCompressor();
	
	@Override
	public byte[] pack(byte[] data){
		var sizeBytes  = sizeBytes(data.length);
		var compressor = getCompressor();
		var dest       = new byte[compressor.maxCompressedLength(data.length) + sizeBytes];
		writeSiz(dest, data.length);
		var compressedSize = compressor.compress(data, 0, data.length, dest, sizeBytes);
		return Arrays.copyOf(dest, compressedSize + sizeBytes);
	}
	
	@Override
	public byte[] unpack(byte[] packedData){
		class Decompressor{
			private static final LZ4FastDecompressor INST = FACTORY_PROVIDER.get().fastDecompressor();
		}
		int orgLen = readSiz(packedData);
		return Decompressor.INST.decompress(packedData, sizeBytes(orgLen), orgLen);
	}
}

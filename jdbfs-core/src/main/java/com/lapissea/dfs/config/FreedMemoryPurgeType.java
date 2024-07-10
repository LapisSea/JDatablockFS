package com.lapissea.dfs.config;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.List;

public enum FreedMemoryPurgeType implements NamedEnum{
	/**
	 * This purge type simply signifies that nothing should be done. This leaves potential data
	 * that may be a valid chunk header and may be a danger for invalid or corrupted pointers. <br>
	 * This is only to be used when the correctness of data is not in question and performance is
	 * important.
	 */
	NO_OP("false"),
	/**
	 * This is a performance intensive option that zeros out every byte that may result in a valid
	 * chunk header to be read. It leaves all other freed data untouched. <br>
	 * This is useful for debugging
	 * and development purposes. Should probably not be used in production.
	 */
	ONLY_HEADER_BYTES,
	/**
	 * This option simply zeros out all data within the range of a chunk. This is pretty fast and will result
	 * in better data compression if any is used. This also has the added benefit of fully destroying data. <br>
	 * Consider using this regardless of the performance if data security is a consideration.
	 */
	ZERO_OUT("true");
	
	private final List<String> names;
	
	FreedMemoryPurgeType(String... names){
		this.names = Iters.concat1N(name(), Iters.from(names).sorted()).collectToFinalList(String::toUpperCase);
	}
	
	@Override
	public List<String> names(){ return names; }
}

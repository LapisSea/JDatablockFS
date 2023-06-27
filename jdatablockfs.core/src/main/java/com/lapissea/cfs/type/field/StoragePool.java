package com.lapissea.cfs.type.field;

public enum StoragePool{
	/**
	 * Values in this storage pool remain as long as the instance is alive.
	 */
	INSTANCE("<<"),
	/**
	 * Values in this storage pool remain as long as there is an IO operation is executing.
	 * Used for fields that are only needed to correctly read another field such as length of an array.
	 */
	IO("IO");
	
	public final String shortName;
	StoragePool(String shortName){ this.shortName = shortName; }
}

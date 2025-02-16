package com.lapissea.dfs.type.field;

public abstract class FieldNames{
	
	public interface Named{
		String getName();
	}
	public static Named name(String name){ return () -> name; }
	
	public static final char GENERATED_FIELD_SEPARATOR = ':';
	
	public static String nonStandard(String baseName, String extension){
		return make(baseName, extension);
	}
	
	private static String make(String base, String extension){
		return base + GENERATED_FIELD_SEPARATOR + extension;
	}
	private static String make(Named base, String extension){
		return make(base.getName(), extension);
	}
	
	public static String collectionLen(Named field)     { return make(field, "len"); }
	public static String numberSize(Named field)        { return make(field, "nSiz"); }
	public static String ID(Named field)                { return make(field, "id"); }
	public static String genericID(Named field)         { return make(field, "typeID"); }
	public static String universeID(Named field)        { return make(field, "localID"); }
	public static String nullFlag(Named field)          { return make(field, "isNull"); }
	public static String companionValueFlag(Named field){ return make(field, "value"); }
	public static String ref(Named field)               { return make(field, "ref"); }
	public static String pack(Named field)              { return make(field, "pack"); }
	public static String modifiable(Named field)        { return make(field, "isMod"); }
}

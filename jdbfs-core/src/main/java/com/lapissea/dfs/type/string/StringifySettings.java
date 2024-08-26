package com.lapissea.dfs.type.string;

import java.util.Objects;

public record StringifySettings(
	boolean doShort, boolean showName, boolean showFieldNames,
	String start, String end, String fieldValueSeparator, String fieldSeparator
){
	
	public static final StringifySettings DEFAULT = new StringifySettings(
		false, true, true,
		"{", "}", "=", ", "
	);
	
	public StringifySettings(boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		this(doShort, !doShort, true, start, end, fieldValueSeparator, fieldSeparator);
	}
	
	public StringifySettings{
		Objects.requireNonNull(start);
		Objects.requireNonNull(end);
		Objects.requireNonNull(fieldValueSeparator);
		Objects.requireNonNull(fieldSeparator);
	}
	
	public static StringifySettings ofDoShort(boolean doShort){
		return DEFAULT.withDoShort(doShort);
	}
	public StringifySettings withDoShort(boolean doShort){
		if(this.doShort == doShort) return this;
		return new StringifySettings(doShort, showName, showFieldNames, start, end, fieldValueSeparator, fieldSeparator);
	}
	public StringifySettings withShowName(boolean showName){
		if(this.showName == showName) return this;
		return new StringifySettings(doShort, showName, showFieldNames, start, end, fieldValueSeparator, fieldSeparator);
	}
	
	public boolean stringsEqual(StringifySettings other){
		return this == other || match(other);
	}
	private boolean match(StringifySettings other){
		return this.start.equals(other.start) &&
		       this.end.equals(other.end) &&
		       this.fieldValueSeparator.equals(other.fieldValueSeparator) &&
		       this.fieldSeparator.equals(other.fieldSeparator);
	}
}

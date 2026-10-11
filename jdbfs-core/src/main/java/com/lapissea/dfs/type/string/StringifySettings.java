package com.lapissea.dfs.type.string;

import java.util.Objects;

public record StringifySettings(
	boolean doShort, boolean showName, boolean showFieldNames,
	String start, String end, String fieldValueSeparator, String fieldSeparator,
	int width, int flatCollectionLimit, int repeatThreshold, int maxCollectionPrint
){
	
	public static final StringifySettings DEFAULT = new StringifySettings(
		false, true, true,
		"{", "}", "=", ", ", 0, 300, 4, 200
	);
	
	public static final int DEFAULT_WIDTH = 100;
	
	public static final StringifySettings PRETTY = new StringifySettings(
		false, true, true,
		"{", "}", ": ", ", ", DEFAULT_WIDTH, 300, 4, 200
	);
	
	public StringifySettings(boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		this(doShort, !doShort, true, start, end, fieldValueSeparator, fieldSeparator, 0, 300, 4, 200);
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
		return new StringifySettings(doShort, showName, showFieldNames, start, end, fieldValueSeparator, fieldSeparator, width, flatCollectionLimit, repeatThreshold, maxCollectionPrint);
	}
	public StringifySettings withShowName(boolean showName){
		if(this.showName == showName) return this;
		return new StringifySettings(doShort, showName, showFieldNames, start, end, fieldValueSeparator, fieldSeparator, width, flatCollectionLimit, repeatThreshold, maxCollectionPrint);
	}
	public StringifySettings withWidth(int width){
		if(this.width == width) return this;
		return new StringifySettings(doShort, showName, showFieldNames, start, end, fieldValueSeparator, fieldSeparator, width, flatCollectionLimit, repeatThreshold, maxCollectionPrint);
	}
	public StringifySettings withFlatCollectionLimit(int flatCollectionLimit){
		if(this.flatCollectionLimit == flatCollectionLimit) return this;
		return new StringifySettings(doShort, showName, showFieldNames, start, end, fieldValueSeparator, fieldSeparator, width, flatCollectionLimit, repeatThreshold, maxCollectionPrint);
	}
	public StringifySettings withRepeatThreshold(int repeatThreshold){
		if(this.repeatThreshold == repeatThreshold) return this;
		return new StringifySettings(doShort, showName, showFieldNames, start, end, fieldValueSeparator, fieldSeparator, width, flatCollectionLimit, repeatThreshold, maxCollectionPrint);
	}
	public StringifySettings withMaxCollectionPrint(int maxCollectionPrint){
		if(this.maxCollectionPrint == maxCollectionPrint) return this;
		return new StringifySettings(doShort, showName, showFieldNames, start, end, fieldValueSeparator, fieldSeparator, width, flatCollectionLimit, repeatThreshold, maxCollectionPrint);
	}
	
	public boolean flat(){
		return width<=0;
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

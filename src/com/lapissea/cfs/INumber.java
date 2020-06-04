package com.lapissea.cfs;

public interface INumber{
	
	interface Mutable extends INumber{
		void setValue(long value);
		
		default void setValue(INumber value){
			setValue(value.getValue());
		}
	}
	
	long getValue();
	
	default boolean equals(long value){
		return getValue()==value;
	}
}

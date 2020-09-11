package com.lapissea.cfs.objects;

import com.lapissea.util.NotNull;

public interface INumber extends Comparable<INumber>{
	
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
	
	@Override
	default int compareTo(@NotNull INumber o){
		return Long.compare(getValue(), o.getValue());
	}
}

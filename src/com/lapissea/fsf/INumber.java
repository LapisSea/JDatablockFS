package com.lapissea.fsf;

public interface INumber{
	
	interface Mutable extends INumber{
		void setValue(long value);
	}
	
	long getValue();
	
}

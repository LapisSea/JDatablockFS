package test;

public interface DataLogger{
	
	void log(MemFrame frame);
	default void finish(){}
}

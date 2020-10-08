package test;

public interface DataLogger{
	
	class Blank implements DataLogger{
		@Override
		public void log(MemFrame frame){ }
		
		@Override
		public void reset(){ }
	}
	
	void log(MemFrame frame);
	
	default void finish(){}
	
	void reset();
}

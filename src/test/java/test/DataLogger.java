package test;

public interface DataLogger{
	
	class Blank implements DataLogger{
		@Override
		public void log(MemFrame frame){ }
		
		@Override
		public void finish(){ }
		
		@Override
		public void reset(){ }
	}
	
	void log(MemFrame frame);
	
	void finish();
	
	void reset();
}

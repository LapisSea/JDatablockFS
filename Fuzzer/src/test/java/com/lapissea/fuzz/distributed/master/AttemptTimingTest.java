package com.lapissea.fuzz.distributed.master;

import com.lapissea.fuzz.distributed.RemoteSequenceResult.ExecutionStats;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class AttemptTimingTest{
	
	@Test
	public void interimAndTerminalSamplesAreNotCountedTwice(){
		var ledger = new AttemptTiming();
		
		assertEquals(ledger.accept("Ryzen", 1, new ExecutionStats(10, 1000)), new ExecutionStats(10, 1000));
		assertEquals(ledger.accept("Ryzen", 1, new ExecutionStats(10, 1000)), ExecutionStats.EMPTY);
		assertEquals(ledger.accept("Ryzen", 1, new ExecutionStats(20, 1800)), new ExecutionStats(10, 800));
		assertEquals(ledger.finish("Ryzen", 1, new ExecutionStats(25, 2400)), new ExecutionStats(5, 600));
		assertEquals(ledger.size(), 0);
	}
	
	@Test
	public void reorderedSamplesAndCreationCost(){
		var ledger = new AttemptTiming();
		
		assertEquals(ledger.accept("local", 1, new ExecutionStats(0, 500)), ExecutionStats.EMPTY);
		assertEquals(ledger.accept("local", 1, new ExecutionStats(1, 800)), new ExecutionStats(1, 800));
		assertEquals(ledger.accept("local", 1, new ExecutionStats(0, 600)), ExecutionStats.EMPTY);
		assertEquals(ledger.accept("local", 1, new ExecutionStats(2, 700)), ExecutionStats.EMPTY);
		assertEquals(ledger.finish("local", 1, new ExecutionStats(1, 1000)), new ExecutionStats(0, 200));
	}
	
	@Test
	public void workersAndAttemptsHaveIndependentCounters(){
		var ledger = new AttemptTiming();
		
		assertEquals(ledger.accept("local", 1, new ExecutionStats(5, 50)), new ExecutionStats(5, 50));
		assertEquals(ledger.accept("Ryzen", 1, new ExecutionStats(5, 100)), new ExecutionStats(5, 100));
		assertEquals(ledger.accept("Ryzen", 2, new ExecutionStats(5, 200)), new ExecutionStats(5, 200));
		assertEquals(ledger.size(), 3);
		ledger.discard("Ryzen", 1);
		assertEquals(ledger.size(), 2);
	}
}

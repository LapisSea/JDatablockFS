package com.lapissea.dfs.run;

import com.lapissea.fuzz.Plan;

import java.io.File;

public final class FuzzingUtils{
	
	public static <State, Action> void stableRun(Plan<State, Action> plan, String name){
		plan.configMod(c -> c.withName(name))
		    .requireStableAction()
		    .runAll().report()
		    .stableFail(8)
		    .runMark()
		    .assertFail();
	}
	public static <State, Action> void stableRunAndSave(Plan<State, Action> plan, String name){
		plan.loadFail(new File("FailCache/" + name))
		    .requireStableAction()
		    .configMod(c -> c.withName(name))
		    .ifHasFail(p -> p.stableFail(8).report()
		                     .clearUnstable()
		                     .runMark()
		                     .assertFail())
		    .runAll().report()
		    .stableFail(8)
		    .saveFail().runMark()
		    .assertFail();
	}
	
}

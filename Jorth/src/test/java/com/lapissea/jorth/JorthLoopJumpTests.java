package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class JorthLoopJumpTests{
	public static void appendTrace(List<Integer> trace, int marker){
		if(trace.size()>=100) throw new IllegalStateException("Loop exceeded trace limit");
		trace.add(marker);
	}

	private static void trace(CodeBlock code, int marker) throws MalformedJorth{
		code.call(JorthLoopJumpTests.class, "appendTrace", args -> args.get("trace").val(marker));
	}

	@Test(timeOut = 10000)
	public void breakSkipsNextCheckAndContinuesAfterLoop() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			var code = cd.function("run").staticAcc().arg(List.class, "trace").body();
			trace(code, 10);
			code.var(int.class, "i").val(0).set("i").loop(check -> {
				trace(check, 20);
				check.get("i").val(4).lessThanOp();
			}, body -> {
				trace(body, 30);
				body.get("i").add(1).set("i");
				body.get("i").val(2).ifEquality(stop -> stop.scope(scoped -> {
					trace(scoped, 40);
					scoped.breakOp();
				}));
				trace(body, 50);
			});
			trace(code, 60);
		});
		var events = new ArrayList<Integer>();
		cls.getMethod("run", List.class).invoke(null, events);
		assertThat(events).containsExactly(10, 20, 30, 50, 20, 30, 40, 60);
	}

	@Test(timeOut = 10000)
	public void continueSkipsRestOfBodyAndRechecksCondition() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			var code = cd.function("run").staticAcc().arg(List.class, "trace").body();
			code.var(int.class, "i").val(0).set("i").loop(check -> {
				trace(check, 10);
				check.get("i").val(3).lessThanOp();
			}, body -> {
				trace(body, 20);
				body.get("i").add(1).set("i");
				body.get("i").val(2).lessThanOp().ifTrue(skip -> skip.scope(scoped -> {
					trace(scoped, 30);
					scoped.continueOp();
				}));
				trace(body, 40);
			});
			trace(code, 50);
		});
		var events = new ArrayList<Integer>();
		cls.getMethod("run", List.class).invoke(null, events);
		assertThat(events).containsExactly(10, 20, 30, 10, 20, 40, 10, 20, 40, 10, 50);
	}

	@Test(timeOut = 10000)
	public void bothBranchesCanJumpToDifferentLoopPoints() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			var code = cd.function("run").staticAcc().arg(List.class, "trace").body();
			code.var(int.class, "i").val(0).set("i").loop(check -> {
				trace(check, 10);
				check.get("i").val(5).lessThanOp();
			}, body -> {
				body.get("i").add(1).set("i");
				body.get("i").val(2).ifEquality(stop -> {
					trace(stop, 20);
					stop.breakOp();
				}).elseRun(next -> {
					trace(next, 30);
					next.continueOp();
				});
			});
			trace(code, 40);
		});
		var events = new ArrayList<Integer>();
		cls.getMethod("run", List.class).invoke(null, events);
		assertThat(events).containsExactly(10, 30, 10, 20, 40);
	}

	@Test(timeOut = 10000)
	public void nestedLoopsTargetNearestLoop() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			var code = cd.function("run").staticAcc().arg(List.class, "trace").body();
			code.var(int.class, "i").val(0).set("i").loop(check -> {
				trace(check, 10);
				check.get("i").val(2).lessThanOp();
			}, outer -> {
				outer.get("i").add(1).set("i");
				outer.var(int.class, "j").val(0).set("j").loop(check -> {
					trace(check, 20);
					check.get("j").val(4).lessThanOp();
				}, inner -> {
					inner.get("j").add(1).set("j");
					inner.get("j").val(1).ifEquality(next -> {
						trace(next, 30);
						next.continueOp();
					});
					trace(inner, 40);
					inner.breakOp();
				});
				trace(outer, 50);
			});
			trace(code, 60);
		});
		var events = new ArrayList<Integer>();
		cls.getMethod("run", List.class).invoke(null, events);
		assertThat(events).containsExactly(10, 20, 30, 20, 40, 50, 10, 20, 30, 20, 40, 50, 10, 60);
	}

	@Test(timeOut = 10000)
	public void conditionCanConditionallyBreakOrContinue() throws Exception{
		for(boolean breakInstead : new boolean[]{false, true}){
			var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
				var code = cd.function("run").staticAcc().arg(List.class, "trace").body();
				code.var(int.class, "i").val(0).set("i").loop(check -> {
					trace(check, 10);
					check.get("i").add(1).set("i");
					check.get("i").val(2).ifEquality(jump -> {
						trace(jump, 20);
						if(breakInstead) jump.breakOp();
						else jump.continueOp();
					});
					check.get("i").val(4).lessThanOp();
				}, body -> trace(body, 30));
				trace(code, 40);
			});
			var events = new ArrayList<Integer>();
			cls.getMethod("run", List.class).invoke(null, events);
			assertThat(events).isEqualTo(breakInstead? List.of(10, 30, 10, 20, 40) : List.of(10, 30, 10, 20, 10, 30, 10, 40));
		}
	}
}

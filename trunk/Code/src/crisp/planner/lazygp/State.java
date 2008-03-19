package crisp.planner.lazygp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import crisp.planningproblem.Action;
import crisp.planningproblem.Domain;
import crisp.planningproblem.Predicate;
import crisp.planningproblem.Problem;
import crisp.planningproblem.goal.Conjunction;
import crisp.planningproblem.goal.Goal;
import crisp.planningproblem.goal.Literal;
import crisp.planningproblem.goal.Universal;
import de.saar.chorus.term.Compound;
import de.saar.chorus.term.Substitution;
import de.saar.chorus.term.Term;

public class State {
	private Domain domain;
	private Problem problem;

	private AtomTable table;
	private ActionInstanceTable actionTable;

	private Set<String> forbiddenPredicates;
	private Set<String> staticPredicates;

	private int step;

	private static final boolean EXPLAIN_USELESSNESS = false;




	/*** data structures for literals ***/

	public static final int LIT_POSITIVE = 1, LIT_NEGATIVE = 2; // bitmasks

	private byte[] literals;

	private boolean canBeTrue(int literalIndex) {
		return (literalIndex < literals.length) && ((literals[literalIndex] & LIT_POSITIVE) == LIT_POSITIVE);
	}

	private boolean canBeFalse(int literalIndex) {
		if( literalIndex >= literals.length ) {
			return true;
		} else {
			return (literals[literalIndex] & LIT_NEGATIVE) == LIT_NEGATIVE;
		}
	}

	private boolean canBe(int literalIndex, boolean value) {
		return value ? canBeTrue(literalIndex) : canBeFalse(literalIndex);
	}


	/*** data structures for mutexes ***/

	private Set<Integer>[] mutexes; // invariant: symmetric relation

	private static int encodeLiteral(int index, boolean polarity) {
		return (index << 1) + (polarity ? 0 : 1);
	}

	private boolean getLiteralPolarity(int index) {
		return (index & 1) == 0;
	}



	private int getLiteralAtom(int goal) {
		return goal >> 1;
	}

	private boolean isKnownAtom(int atom) {
		return atom < literals.length;
	}

	private boolean isMutex(int index1, int index2) {
		if( mutexes.length <= index1 ) {
			// literal1 not yet known; it is mutex with lit2 iff it is positive
			return getLiteralPolarity(index1);
		} else if( mutexes.length <= index2 ) {
			// dito for literal2
			return getLiteralPolarity(index2);
		} else if( mutexes[index1] == null ) {
			return false;
		} else {
			return mutexes[index1].contains(index2);
		}	
	}

	public boolean isMutex(int literal1, boolean polarity1, int literal2, boolean polarity2) {
		int index1 = encodeLiteral(literal1, polarity1);
		int index2 = encodeLiteral(literal2, polarity2);

		return isMutex(index1, index2);
	}

	private void setMutex(int literal1, boolean polarity1, int literal2, boolean polarity2, boolean isMutex) {
		int index1 = encodeLiteral(literal1, polarity1);
		int index2 = encodeLiteral(literal2, polarity2);

		if( mutexes[index1] == null ) {
			mutexes[index1] = new HashSet<Integer>();
		}

		if( isMutex ) {
			mutexes[index1].add(index2);
		} else {
			mutexes[index1].remove(index2);
		}

		if( mutexes[index2] == null ) {
			mutexes[index2] = new HashSet<Integer>();
		}

		if( isMutex ) {
			mutexes[index2].add(index1);
		} else {
			mutexes[index2].remove(index1);
		}
	}


	/*** set of applicable action instances; only keep one copy of this around because
	 * it grows monotonically from state to state ***/
	//private static Set<ActionInstance> applicableInstances = new HashSet<ActionInstance>(); 


	/*** data structures for linking this state to the previous action layer ***/

	private ActionLayer previousLayer;
	private List<Integer>[] causes;

	private static final List<Integer> NO_CAUSES = new ArrayList<Integer>();

	private void addCause(int literalIndex, boolean polarity, int instix) {
		int index = encodeLiteral(literalIndex, polarity);

		if( causes[index] == null ) {
			causes[index] = new ArrayList<Integer>();
		}

		causes[index].add(instix);
	}

	private List<Integer> getCauses(int literal, boolean polarity) {
		List<Integer> ret = causes[encodeLiteral(literal, polarity)];

		if( ret != null ) {
			return ret;
		} else {
			return NO_CAUSES;
		}
	}






	// Create a new state from an old state and an action layer.  This updates
	// the literals that may be true or false and the literal mutexes.
	State(State oldState, ActionLayer previousLayer) {
		this.domain = oldState.domain;
		this.problem = oldState.problem;
		step = oldState.step + 1;

		this.previousLayer = previousLayer;
		this.forbiddenPredicates = oldState.forbiddenPredicates;
		this.staticPredicates = oldState.staticPredicates;

		table = oldState.table;
		actionTable = oldState.actionTable;
		causes = new List[2*table.size()]; // table contains all literals for the new state at this point
		mutexes = new Set[2*table.size()]; 

		literals = updateLiterals(oldState.literals, previousLayer);

		computeMutexes();
	}


	public State(Problem problem) {
		this.problem = problem;
		this.domain = problem.getDomain();
		step = 0;

		staticPredicates = new HashSet<String>();
		for( Predicate pred : domain.getStaticPredicates() ) {
			staticPredicates.add(pred.getLabel());
		}

		previousLayer = null;

		table = new AtomTable(problem);
		actionTable = new ActionInstanceTable();

		// ASSUMPTION: initial state consists only of true literals
		literals = table.setTrueLiterals(problem.getInitialState());
		mutexes = new Set[2*table.size()];
		causes = new List[2*table.size()];

		forbiddenPredicates = new HashSet<String>();
		analyzeGoal();
	}





	/*** checking for goal state ***/

	public boolean isGoalState() {
		for( int i = 0; i < literals.length; i++ ) {
			if( isForbiddenLiteral(i) ) {
				// forbidden literals must be able to be false
				if( !canBeFalse(i) ) {
					return false;
				}

				// ... and must be false at the same time as all other forbidden literals
				for( int j = i+1; j < literals.length; j++ ) {
					if( isForbiddenLiteral(j) && isMutex(i, false, j, false) ) {
						return false;
					}
				}
			}
		}

		return true;
	}

	private void analyzeGoal() {
		Goal goal = problem.getGoal();

		if (goal instanceof Conjunction) {
			List<Goal> goals = ((Conjunction) goal).getConjuncts();

			for( Goal conj : goals ) {
				if( conj instanceof Universal ) {
					Goal scope = ((Universal) conj).getScope();

					if( scope instanceof Literal ) {
						Literal lit = ((Literal) scope);
						if( lit.getPolarity() == false ) {
							forbiddenPredicates.add(((Compound) lit.getAtom()).getLabel());
							continue;
						}
					}
				}

				throw new RuntimeException("Can't handle this goal!");
			}
		} else {
			throw new RuntimeException("Can't handle this goal!");
		}
	}

	private boolean isForbiddenLiteral(int index) {
		return forbiddenPredicates.contains(table.get(index).getLabel());
	}



	/*** computing applicable instances ***/

	public ActionLayer getNextActionLayer() {
		int maxActionIndex = getMaxApplicableActionInstance();
		ActionLayer ret = new ActionLayer(this, maxActionIndex);

		for( int i = 0; i <= maxActionIndex; i++ ) {
			actionTable.get(i).computeEffects(table);
		}

		ret.computeMutexActionInstances();

		return ret;
	}

	public int getMaxApplicableActionInstance() {
		int maxActionIndex = 0;

		for( Action a : problem.getDomain().getActions() ) {
			int newActionIndex = computeApplicableInstances(a.getPrecondition().getGoalList(problem), new Substitution(), new ArrayList<Integer>(), new ArrayList<Boolean>(), a);
			if( newActionIndex > maxActionIndex ) {
				maxActionIndex = newActionIndex;
			}
		}

		return maxActionIndex;
	}

	private int computeApplicableInstances(List<Literal> goalList, Substitution subst, List<Integer> indicesSoFar, List<Boolean> polaritiesSoFar, Action a) {
		if( goalList.isEmpty() ) {
			return actionTable.ensureKnown(new ActionInstance(a, subst, indicesSoFar, polaritiesSoFar, problem));
		} else {
			Literal goal = goalList.remove(goalList.size()-1);
			Compound c = (Compound) subst.apply(goal.getAtom());
			int ret = 0;

			if( goal.getPolarity() ) {
				literalsLoop:
					for( int i = 0; i < literals.length; i++ ) {
						// OPTIMIZATION: only look at atoms for correct predicate.
						if( canBeTrue(i) ) {
							// skip i if it is mutex with any precondition we have picked so far
							for( Integer j : indicesSoFar ) {
								if( isMutex(i, true, j, true) ) {
									continue literalsLoop;
								}
							}

							Compound trueAtom = table.get(i);
							Substitution unifier = trueAtom.getUnifier(c);

							if( unifier != null ) {
								indicesSoFar.add(i);
								polaritiesSoFar.add(Boolean.TRUE);

								int candidate = computeApplicableInstances(goalList, subst.concatenate(unifier), indicesSoFar, polaritiesSoFar, a);
								if( candidate > ret ) {
									ret = candidate;
								}

								indicesSoFar.remove(indicesSoFar.size()-1);
								polaritiesSoFar.remove(polaritiesSoFar.size()-1);
							}
						}
					}
			} else {
				throw new RuntimeException("I don't know how to deal with negative preconditions!");
			}

			goalList.add(goal);

			return ret;
		}
	}


	/*** action effects ***/



	// OPT: compute mutexes for the new state as we go along
	private byte[] updateLiterals(byte[] literals, ActionLayer previousLayer) {
		// initialize return array
		byte[] ret = new byte[table.size()];
		for( int i = 0; i < literals.length; i++ ) {
			ret[i] = literals[i];
		}
		for( int i = literals.length; i < ret.length; i++ ) {
			ret[i] = State.LIT_NEGATIVE;
		}

		// update possible polarities and add causes
		for( int instix = 0; instix < previousLayer.getNumActionInstances(); instix++ ) {
			ActionInstance inst = actionTable.get(instix);

			for( int i = 0; i < inst.getEffectIndices().length; i++ ) {
				ret[inst.getEffectIndices()[i]] |= (inst.getEffectPolarities()[i] ? State.LIT_POSITIVE : State.LIT_NEGATIVE);
				addCause(inst.getEffectIndices()[i], inst.getEffectPolarities()[i], instix);
			}
		}

		return ret;
	}



	/*** literal mutexes ***/

	private void computeMutexes() {
		for( int i1 = 0; i1 < literals.length; i1++ ) {
			/*
			if( table.get(i1).getLabel().equals("**equals**")) {
				continue;
			}
			 */

			for( int b1 = 0; b1 <= 1; b1++ ) {
				boolean pol1 = (b1 == 0);

				if( canBe(i1, pol1) ) {
					for( int i2 = 0; i2 < literals.length; i2++ ) {
						/*
						if( table.get(i2).getLabel().equals("**equals**")) {
							continue;
						}
						 */

						for( int b2 = 0; b2 <= 1; b2++ ) {
							boolean pol2 = (b2 == 0);

							if( canBe(i2, pol2)) {
								boolean isMutex = true;

								// if the candidate pair was known in the previous state and even
								// not mutex then, then it is still not mutex now (do it with 2x noop)
								State previousState = previousLayer.getPreviousState();
								boolean literal1WasExplicitlyRepresented = (i1 < previousState.literals.length);
								boolean literal2WasExplicitlyRepresented = (i2 < previousState.literals.length);

								if( literal1WasExplicitlyRepresented
										&& literal2WasExplicitlyRepresented
										&& !previousState.isMutex(i1, pol1, i2, pol2) ) {
									isMutex = false;
								}


								// If at least one of the literals is a negative literal that was implicitly
								// satisfied in the previous state (because it had never been true so far),
								// and the other literal could have been true in the previous state, then
								// the previous state silently assumed them to be not mutex, so they are
								// still not mutex now.
								if( (!pol1 && !literal1WasExplicitlyRepresented && previousState.canBe(i2, pol2) )
										|| (!pol2 && !literal2WasExplicitlyRepresented && previousState.canBe(i1, pol1)) ) {
									isMutex = false;
								}

								// iterate over all action instances that establish literal 1; if
								// we find one which is not mutex with noop-literal-2, then the
								// literals are not mutex
								if( isMutex ) {
									isMutex = updateMutexBecauseOfNoop(i1, pol1, i2, pol2, isMutex);
								}

								if( isMutex ) {
									isMutex = updateMutexBecauseOfNoop(i2, pol2, i1, pol1, isMutex);
								}


								// iterate over all pairs of action instances; if we find a non-mutex action
								// pair, then the literals are not mutex
								if( isMutex ) {
									INSTANCE_PAIRS:
										for( Integer inst1 : getCauses(i1, pol1) ) {
											for( Integer inst2 : getCauses(i2, pol2) ) {
												if( ! previousLayer.isMutex(inst1, inst2) ) {
													isMutex = false;
													break INSTANCE_PAIRS;
												}
											}
										}
								}

								if( isMutex ) {
									setMutex(i1, pol1, i2, pol2, true);
								}
							}
						}
					}
				}
			}
		}
	}



	// determine whether non-mutexness of lit1 and lit2 is established via the
	// action pairs (a,noop-lit2), for all explicit actions a that establish lit1
	private boolean updateMutexBecauseOfNoop(int i1, boolean pol1, int i2, boolean pol2, boolean isMutex) {
		State previousState = previousLayer.getPreviousState();
		boolean literal2WasExplicitlyRepresented = (i2 < previousState.literals.length);

		// noop-lit2 is not suitable for establishing non-mutex if it wasn't applicable
		// in the previous state. This cover the special case where lit2 is a positive
		// implicit literal.
		if( !previousState.canBe(i2, pol2) ) {
			return isMutex;
		}

		for( Integer inst1ix : getCauses(i1, pol1) ) {
			ActionInstance inst1 = actionTable.get(inst1ix);
			boolean canEstablishNonMutex = true;

			// no precondition of the action instance must be mutex
			// with the precondition of noop-literal-2 (namely, literal-2)
			if( literal2WasExplicitlyRepresented ) {
				// if literal2 was implicit, then it must be negative (we caught the positive
				// case above), and hence it isn't mutex with anything

				for( int i = 0; i < inst1.getPreconditionIndices().length; i++ ) {
					if( previousState.isMutex(inst1.getPreconditionIndices()[i], inst1.getPreconditionPolarities()[i], i2, pol2) ) {
						canEstablishNonMutex = false;
						break;
					}
				}
			}

			// not(literal2) must not be an effect of the action instance
			if( canEstablishNonMutex ) {
				for( int i = 0; i < inst1.getEffectIndices().length; i++ ) {
					if( (inst1.getEffectIndices()[i] == i2) && (inst1.getEffectPolarities()[i] != pol2) ) {
						canEstablishNonMutex = false;
						break;
					}
				}
			}

			if( canEstablishNonMutex ) {
				isMutex = false;
				break;
			}
		}

		return isMutex;
	}

	/*** auxiliary ***/


	public Problem getProblem() {
		return problem;
	}


	public String toString() {
		StringBuilder buf = new StringBuilder();

		buf.append("State after " + step + " steps:\n");
		buf.append("Literals:\n");
		for( int i = 0; i < literals.length; i++ ) {
			Term t = table.get(i);
			String tString = t.toString();

			if( canBeTrue(i) ) {
				buf.append(" " + tString);
			}

			if( canBeFalse(i) ) {
				buf.append(" ~" + tString);
			}
		}

		buf.append("\n");

		buf.append("Mutex literals:\n");
		for( int i = 0; i < literals.length; i++ ) {
			Term t = table.get(i);

			if( canBeTrue(i)) {
				buf.append(i + " " + t.toString() + " is mutex with:");
				for( int j = 0; j < literals.length; j++ ) {
					Term t2 = table.get(j);

					if( isMutex(i, true, j, true) ) {
						buf.append(" " + t2);
					}

					if( isMutex(i, true, j, false) ) {
						buf.append(" ~" + t2);
					}
				}
				buf.append("\n");

				/*
				if( mutexes[encodeLiteral(i, true)] != null ) {
					buf.append(i + " " + t.toString() + " is mutex with:");
					buf.append(decodeLiterals(mutexes[encodeLiteral(i, true)], new HashSet<Integer>()));
					buf.append("\n");
				}
				 */
			}

			if( canBeFalse(i)) {
				buf.append(i + " ~" + t.toString() + " is mutex with:");
				for( int j = 0; j < literals.length; j++ ) {
					Term t2 = table.get(j);

					if( isMutex(i, false, j, true) ) {
						buf.append(" " + t2);
					}

					if( isMutex(i, false, j, false) ) {
						buf.append(" ~" + t2);
					}
				}
				buf.append("\n");
			}
		}



		return buf.toString();
	}

	AtomTable getTable() {
		return table;
	}

	ActionInstanceTable getActionTable() {
		return actionTable;
	}

	/*
	public Collection<ActionInstance> getUsefulActionInstances(Set<Integer> goals) {
		Set<ActionInstance> ret = new HashSet<ActionInstance>();
		Set<Integer> goalsCopy = new HashSet<Integer>(goals);
		Set<Integer> deleted = new HashSet<Integer>();

		if( previousLayer == null ) {
			// first state
			return ret;
		}

		State previousState = previousLayer.getPreviousState();


		for( Integer goal : goals ) {
			//System.err.println("\nmutexes of canadj(o-1): " + decodeLiterals(previousState.mutexes[encodeLiteral(106, true)], new HashSet<Integer>()));

			if( goal < causes.length && causes[goal] != null ) {
				for( Integer instix : causes[goal] ) {
					ActionInstance inst = actionTable.get(instix);
					boolean isUseful = true; 

					//System.err.println("consider: " + inst);

					// temporarily remove goals that are achieved by inst
					deleted.clear();
					for( int i = 0; i < inst.getEffectIndices().length; i++ ) {
						int index = encodeLiteral(inst.getEffectIndices()[i], inst.getEffectPolarities()[i]);

						if( goalsCopy.contains(index)) {
							goalsCopy.remove(index);
							deleted.add(index);
						}
					}

					// check that inst is independent of all no-op actions that are
					// necessary to establish the remaining goals
					for( int i = 0; isUseful && i < inst.getPreconditionIndices().length; i++ ) {
						if( goalsCopy.contains(encodeLiteral(inst.getPreconditionIndices()[i], !inst.getPreconditionPolarities()[i])) ) {
							//System.err.println(" - precondition " + table.get(inst.getPreconditionIndices()[i]) + " contradicts a goal");
							isUseful = false;
						}
					}

					for( int i = 0; isUseful && i < inst.getEffectIndices().length; i++ ) {
						if( goalsCopy.contains(encodeLiteral(inst.getEffectIndices()[i], !inst.getEffectPolarities()[i])) ) {
							//System.err.println(" - effect " + table.get(inst.getEffectIndices()[i]) + " contradicts a goal");
							isUseful = false;
						}
					}

					// check that no precondition of inst is mutex with any of the
					// remaining goals
					// OPT then experiment with iterating over the goal elements first instead
					for( int i = 0; isUseful && i < inst.getPreconditionIndices().length; i++ ) {
						int index = encodeLiteral(inst.getPreconditionIndices()[i], inst.getPreconditionPolarities()[i]);

						if( previousState.mutexes[index] != null && !Collections.disjoint(previousState.mutexes[index], goalsCopy) ) {
							//System.err.println(" - precondition " + decodeLiteral(index) + " is mutex with a goal");
							//System.err.println("   * atom index: " + inst.getPreconditionIndices()[i]);
							//System.err.println("   * polarity: " + inst.getPreconditionPolarities()[i]);
							//System.err.println("   * mutexes of this precondition were: " + decodeLiterals(previousState.mutexes[index], goalsCopy));
							isUseful = false;
						}
					}

					// put the goals back in
					goalsCopy.addAll(deleted);

					if( isUseful ) {
						ret.add(inst);
					}
				}
			}
		}

		return ret;
	}
	 */

	public Collection<ActionInstance> getUsefulActionInstances(Set<Integer> goalsAsSet, int len) {
		if( previousLayer == null ) {
			return new HashSet<ActionInstance>();
		}

		boolean[] goals = new boolean[len];
		for( Integer x : goalsAsSet ) {
			goals[x] = true;
		}

		return getUsefulActionInstances(goals);

	}


	public Collection<ActionInstance> getUsefulActionInstances(boolean[] goals) {
		Set<ActionInstance> ret = new HashSet<ActionInstance>();
		Set<Integer> consideredAction = new HashSet<Integer>();
		boolean[] deleted = new boolean[goals.length];

		if( previousLayer == null ) {
			// first state
			return ret;
		}

		State previousState = previousLayer.getPreviousState();

		for( int goal = 0; goal < goals.length; goal++ ) {
			if( goals[goal] ) {

				if( goal < causes.length && causes[goal] != null ) {
					for( Integer instix : causes[goal] ) {
						if( !consideredAction.contains(instix) ) {
							consideredAction.add(instix);

							ActionInstance inst = actionTable.get(instix);
							boolean isUseful = true; 

							if( EXPLAIN_USELESSNESS ) {
								System.err.println("\nconsider: " + inst);
							}

							// temporarily remove goals that are achieved by inst
							for( int i = 0; i < goals.length; i++ ) {
								deleted[i] = goals[i];
							}

							for( int i = 0; i < inst.getEffectIndices().length; i++ ) {
								int index = encodeLiteral(inst.getEffectIndices()[i], inst.getEffectPolarities()[i]);

								if( goals[index] ) {
									goals[index] = false;
								}
							}

							if( EXPLAIN_USELESSNESS ) {
								System.err.print("temporary goals:");
								for( int i = 0; i < goals.length; i++ ) {
									if( goals[i] ) {
										System.err.print(" " + decodeLiteral(i));
									}
								}
								System.err.println();
							}

							// check that inst is independent of all no-op actions that are
							// necessary to establish the remaining goals
							for( int i = 0; isUseful && i < inst.getPreconditionIndices().length; i++ ) {
								if( goals[encodeLiteral(inst.getPreconditionIndices()[i], !inst.getPreconditionPolarities()[i])] ) {
									if( EXPLAIN_USELESSNESS ) {
										System.err.println(" - precondition " + table.get(inst.getPreconditionIndices()[i]) + " contradicts a goal");
									}

									isUseful = false;
								}
							}

							for( int i = 0; isUseful && i < inst.getEffectIndices().length; i++ ) {
								if( goals[encodeLiteral(inst.getEffectIndices()[i], !inst.getEffectPolarities()[i])] ) {
									if( EXPLAIN_USELESSNESS ) {
										System.err.println(" - effect " + table.get(inst.getEffectIndices()[i]) + " contradicts a goal");
									}

									isUseful = false;
								}
							}

							// check that no precondition of inst is mutex with any of the
							// remaining goals
							// OPT then experiment with iterating over the goal elements first instead
							if( EXPLAIN_USELESSNESS ) { 
								if( isUseful ) {
									System.err.println("  check preconditions of " + inst + " for mutex goals");
								}
							}

							for( int i = 0; isUseful && i < inst.getPreconditionIndices().length; i++ ) {
								int index = encodeLiteral(inst.getPreconditionIndices()[i], inst.getPreconditionPolarities()[i]);

								if( EXPLAIN_USELESSNESS ) {
									System.err.println("    precond: " + decodeLiteral(index));
									System.err.println("    mutexes of this precondition : " + (previousState.mutexes[index] == null ? null : decodeLiterals(previousState.mutexes[index], new HashSet<Integer>())));
								}

								if( previousState.mutexes[index] != null ) {
									for( int j = 0; j < goals.length && isUseful; j++ ) {
										if( goals[j] ) {
											if( !previousState.isKnownAtom(getLiteralAtom(j)) ) {
												if( getLiteralPolarity(j) ) {
													if( EXPLAIN_USELESSNESS ) {
														System.err.println("    -> implicit mutex with goal " + decodeLiteral(j));
													}

													isUseful = false;
												}
											} else {
												if( previousState.mutexes[index].contains(j) ) {
													if( EXPLAIN_USELESSNESS ) {
														System.err.println("    -> mutex with goal " + decodeLiteral(j));
													}

													isUseful = false;
												}
											}
										}
									}
								}
							}

							// put the goals back in
							for( int i = 0; i < goals.length; i++ ) {
								goals[i] = deleted[i];
							}

							if( isUseful ) {
								if( EXPLAIN_USELESSNESS ) {
									System.err.println("        -> action is useful!");
								}

								ret.add(inst);
							}
						}
					}
				}
			}
		}
		return ret;
	}

	private String decodeGoals(boolean[] goals) {
		StringBuilder ret = new StringBuilder();

		for( int i = 0; i < goals.length; i++ ) {
			if( goals[i]) {
				ret.append(" " + decodeLiteral(i));
			}
		}

		return ret.toString();
	}

	String decodeLiteral(int index) {
		int lit = (index >> 1);
		boolean pol = (index & 1) == 0;

		return (pol ? " " : " ~") + table.get(lit);
	}

	String decodeLiterals(Set<Integer> literals, Set<Integer> highlight) {
		StringBuilder ret = new StringBuilder();

		for( Integer m : literals ) {
			int lit = (m >> 1);
			boolean pol = (m & 1) == 0;

			ret.append((pol ? " " : " ~") +
					(highlight.contains(m) ? table.get(lit).toString().toUpperCase() : table.get(lit)));
		}

		return ret.toString();
	}

	public Set<Integer> getGoalLiterals() {
		Set<Integer> ret = new HashSet<Integer>();

		for( int i = 0; i < literals.length; i++ ) {
			int negativeLiteral = encodeLiteral(i, false);
			if( isForbiddenLiteral(i) && ! isTrivialGoal(negativeLiteral) ) {
				// don't bother to look at literals that couldn't be true in the first place
				ret.add(negativeLiteral);
			}
		}

		//System.err.println("Goal literals: " + decodeLiterals(ret, new HashSet<Integer>()));

		return ret;
	}

	public Set<Integer> updateGoals(Set<Integer> goals, ActionInstance inst) {
		Set<Integer> ret = new HashSet<Integer>(goals);


		// remove the action's effects
		for( int i = 0; i < inst.getEffectIndices().length; i++ ) {
			ret.remove(encodeLiteral(inst.getEffectIndices()[i], inst.getEffectPolarities()[i]));
		}

		// add the action's preconditions
		for( int i = 0; i < inst.getPreconditionIndices().length; i++ ) {
			int atom = inst.getPreconditionIndices()[i];
			boolean polarity = inst.getPreconditionPolarities()[i];
			int goal = encodeLiteral(atom, polarity);

			// ... but only if it's nontrivial.
			if( !getPreviousState().isTrivialGoal(goal) ) {
				ret.add(goal);
			}
		}

		return ret;
	}

	public boolean isInitialState() {
		return previousLayer == null;
	}

	public State getPreviousState() {
		return previousLayer.getPreviousState();
	}

	public boolean isUnsatisfiableGoal(int goal) {
		int atom = getLiteralAtom(goal);
		//System.err.println("literals of " + goal + " is " + literals[goal]);
		return !canBe(atom, getLiteralPolarity(goal));
	}


	public boolean isTrivialGoal(int goal) {
		int atom = getLiteralAtom(goal);
		return !canBe(atom, !getLiteralPolarity(goal));
	}

	public int getStep() {
		return step;
	}


	public int getFirstPossibleState(int atom, boolean polarity) {
		if( !canBe(atom, polarity) ) {
			return -1;
		} else if( previousLayer == null ) {
			return 0;
		} else if( !getPreviousState().canBe(atom, polarity) ) {
			return step;
		} else {
			return getPreviousState().getFirstPossibleState(atom, polarity);
		}
	}
	
	public int getFirstPossibleStateWithPreconditions(ActionInstance inst) {
		int ret = -1;
		
		for( int i = 0; i < inst.getPreconditionIndices().length; i++ ) {
			int s = getFirstPossibleState(inst.getPreconditionIndices()[i], inst.getPreconditionPolarities()[i]);
			
			if( s == -1 ) {
				return -1;
			} else if( s > ret ) {
				ret = s;
			}
		}
		
		return ret;
	}
}
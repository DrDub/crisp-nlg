package crisp.planningproblem.codec;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

import javax.xml.xpath.XPathExpressionException;

import crisp.planningproblem.Action;
import crisp.planningproblem.DurativeAction;
import crisp.planningproblem.Domain;
import crisp.planningproblem.Predicate;
import crisp.planningproblem.Problem;
import crisp.planningproblem.TypeHierarchy;
import crisp.planningproblem.effect.Effect;
import crisp.planningproblem.goal.Goal;
import de.saar.basic.StringTools;
import de.saar.chorus.term.Term;

public class CostPddlOutputCodec extends PddlOutputCodec {
    
    @Override    
    public void writeDomain(Domain domain, PrintWriter dw) {
        dw.println("(define (domain " + domain.getName() + ")");
        dw.println("        (:requirements " + StringTools.join(domain.getRequirements(), " ") + " :action-costs )");        
        dw.print("        (:types");
        for( String type : domain.getTypeHierarchy().getTypes() ) {
            if( !type.equals(TypeHierarchy.TOP) ) {
                dw.print("  " + type);
                if( ! domain.getTypeHierarchy().getDirectSupertype(type).equals(TypeHierarchy.TOP) ) {
                    dw.print(" - " + domain.getTypeHierarchy().getDirectSupertype(type));
                }
            }
        }
        dw.println(")");
        
        dw.println("       (:constants");
        for( String constant : domain.getUniverse().keySet()) {
            dw.println("         " + constant + " - " + domain.getUniverse().get(constant));
        }
        dw.println("       )");
        
        dw.println("       (:predicates");
        for( Predicate pred : domain.getPredicates() ) {
            if( !pred.getLabel().equals("**equals**")) {
                dw.println("         (" + pred.getLabel() + " " + pred.getVariables().toLispString() + ")");
            }
        }
        dw.println("        )");        
       
        dw.println("        (:functions (total-cost) - number)\n");
        
        for( Action a : domain.getActions() ) {
            dw.println("\n" + toPddlString(a));
        }
        
        dw.println(")");
        dw.flush(); //otherwise output is incomplete 
    }
    
    @Override
    protected String toPddlString(Action action) {
        StringBuffer buf = new StringBuffer();
        String prefix = "      ";
        
        boolean isDurativeAction = (action instanceof DurativeAction);
        
        buf.append("   (:action " + action.getPredicate().getLabel() + "\n");
        buf.append(prefix + ":parameters (" + action.getPredicate().getVariables().toLispString() + ")\n");
        buf.append(prefix + ":precondition " + toPddlString(action.getPrecondition()) + "\n");
        buf.append(prefix + ":effect " + toPddlString(action.getEffect()) + "\n");
        if (isDurativeAction){           
            buf.append("(increase (total-cost) ");
            buf.append(((DurativeAction) action).getDuration());
            buf.append(")");
        }
        
        buf.append(") \n      )\n");
        
        return buf.toString();
    }
    
    @Override
    protected String toPddlString(Effect effect) {
        if( effect instanceof crisp.planningproblem.effect.Conjunction ) {
            crisp.planningproblem.effect.Conjunction conj = (crisp.planningproblem.effect.Conjunction) effect;
            StringBuffer buf = new StringBuffer("(and");
            
            for( Effect conjunct : conj.getConjuncts() ) {
                buf.append(" " + toPddlString(conjunct));
            }
            
            //buf.append(")");
            
            return buf.toString();
        } else if( effect instanceof crisp.planningproblem.effect.Conditional ) {
            crisp.planningproblem.effect.Conditional cond = (crisp.planningproblem.effect.Conditional) effect;
             return "(when " + toPddlString(cond.getCondition()) + " " + toPddlString(cond.getEffect()) + ")";
        } else if( effect instanceof crisp.planningproblem.effect.Universal ) {
            crisp.planningproblem.effect.Universal univ = (crisp.planningproblem.effect.Universal) effect;
            return "(forall (" + univ.getVariables().toLispString() + ") " + toPddlString(univ.getScope()) + ")";
        } else if( effect instanceof crisp.planningproblem.effect.Literal ) {
            crisp.planningproblem.effect.Literal lit = (crisp.planningproblem.effect.Literal) effect;
            return (lit.getPolarity()?"":"(not ") + lit.getAtom().toLispString().replace("**equals**", "=") + (lit.getPolarity()?"":")");
        } else {
            return null;
        }
    }
}
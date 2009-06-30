package crisp.evaluation;

import crisp.planningproblem.Domain;
import crisp.planningproblem.Problem;

import java.io.IOException;

import de.saar.chorus.term.Term; 

import java.util.List;

/**
 * Specify an interface for planners.
 */
public interface PlannerInterface {            
    
    public List<Term> runPlanner(Domain domain, Problem problem) throws Exception;
        
    public long getPreprocessingTime();
    public long getSearchTime();    
    
}

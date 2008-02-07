package dlgre;

import scala.collection.mutable.Queue;

import dlgre.formula._;

class BisimulationClassesComputer(graph:Graph) {
   val queue = new Queue[Option[Subset]]
   
   def compute = {
     queue += Some(new Subset(Top(), graph));
     
     // split over all (positive) literals
     graph.getAllPredicates.foreach { p =>
     	//println("\nProcessing predicate: " + p);
             
        forallQueue(queue, { (subset, queue) =>
          if( subset.canSplitOverLiteral(p, true) ) {
            val splittings = subset.splitOverLiteral(p, true);
                           
            //println("  -> split over " + p + ", new subsets: " + splittings);
                           
            queue += Some(splittings._1);
            queue += Some(splittings._2);
          } else {
            queue += Some(subset);
          }
        });
     }
     
     
     // now repeatedly split over roles to distinguished subsets
     var oldQueue : List[Subset] = Nil;
     var newQueue = extractQueue;
     
     do {
       //println ("\n\n ********** PASS ************\n\n");
       
       oldQueue = newQueue;
       splitOverRoles;
       newQueue = extractQueue;
     } while( oldQueue != newQueue );

     newQueue;
   }
   
   def forallQueue(q : Queue[Option[Subset]], proc : (Subset,Queue[Option[Subset]]) => Unit) = {
     var finished = false;
     q += None;
     
     while(!finished) {
       val el = q.dequeue
       
       el match {
            case None => finished = true;
            case Some(subset) => proc(subset,q);
       }
     }
   }
   
   def extractQueue = {
     (for( val x <- queue.elements if x.isInstanceOf[Some[Subset]] ) 
       yield (x match { case Some(subset) => subset; case None => new Subset(Top(), graph) })).toList
   }
   
   def splitOverRoles = {
     val elements = extractQueue;
     val roles = graph.getAllRoles;
     val localQueue = new Queue[Option[Subset]];
     
     queue.clear;
     
     elements.foreach { el =>
        //println("\n\nConsider element: " +  el);
        
     	localQueue.clear;
        localQueue += Some(el);
        
        if( el.getIndividuals.size > 1 ) {
          for( val role <- roles; val sub1 <- elements; val sub2 <- elements if sub1 != sub2) {
            //println("Consider split over " + role + ", " + sub1 + ", " + sub2);
            //println("   (local queue: " + localQueue + ")");
          
            forallQueue(localQueue, { (subset, q) =>
               if( subset.canSplitOverRole(role, (sub1,sub2)) ) {
                 val splittings = subset.splitOverRole(role, (sub1,sub2));

                 //println("    -> split! new: " + splittings);

                 q += Some(splittings._1);
                 q += Some(splittings._2);
               } else {
                 q += Some(subset);
               }
            });
          }
        }
        
        //println("Finished, local queue is: " + localQueue);
        
        queue ++= localQueue;
     }
   }
}
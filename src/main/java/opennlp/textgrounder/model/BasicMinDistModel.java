/*
 * Basic Minimum Distance model. For each toponym, the location is selected that minimizes the total distance to some disambiguation
 * of the other toponyms in the same document.
 */

package opennlp.textgrounder.model;

import opennlp.textgrounder.text.*;
import opennlp.textgrounder.topo.*;
import java.util.*;

public class BasicMinDistModel extends Model {

    @Override
    public StoredCorpus disambiguate(StoredCorpus corpus) {
        for(Document<StoredToken> doc : corpus) {
            for(Sentence<StoredToken> sent : doc) {
                for(Token token : sent.getToponyms()) {
                    //if(token.isToponym()) {
                        Toponym toponym = (Toponym) token;

                        basicMinDistDisambiguate(toponym, doc);
                    //}
                }
            }
        }
        return corpus;
    }

    /*
     * Sets the selected index of toponymToDisambiguate according to the Location with the minimum total
     * distance to some disambiguation of all the Locations of the Toponyms in doc.
     */
    private void basicMinDistDisambiguate(Toponym toponymToDisambiguate, Document<StoredToken> doc) {        
        //HashMap<Location, Double> totalDistances = new HashMap<Location, Double>();
        List<Double> totalDistances = new ArrayList<Double>();

        // Compute the total minimum distances from each candidate Location of toponymToDisambiguate to some disambiguation
        // of all the Toponyms in doc; store these in totalDistances
        for(Location curLoc : toponymToDisambiguate) {
            Double totalDistSoFar = 0.0;
            int seen = 0;

            for(Sentence<StoredToken> sent : doc) {
                for(Token token : sent.getToponyms()) {
                    //if(token.isToponym()) {
                        Toponym otherToponym = (Toponym) token;

                        /* We don't want to compute distances if this other toponym is the
                         * same as the current one, or if it has no candidates. */  
                        if (!otherToponym.equals(toponymToDisambiguate) && otherToponym.getAmbiguity() > 0) {
                          double minDist = Double.MAX_VALUE;
                          for(Location otherLoc : otherToponym) {
                              double curDist = curLoc.distance(otherLoc);
                              if(curDist < minDist) {
                                  minDist = curDist;
                              }
                          }
                          totalDistSoFar += minDist;
                          seen++;
                        }
                    //}
                }
            }

            /* Abstain if we haven't seen any other toponyms. */
            totalDistances.add(seen > 0 ? totalDistSoFar : Double.MAX_VALUE);
        }

        // Find the overall minimum of all the total minimum distances computed above
        double minTotalDist = Double.MAX_VALUE;
        int indexOfMin = -1;
        for(int curLocIndex = 0; curLocIndex < totalDistances.size(); curLocIndex++) {
            double totalDist = totalDistances.get(curLocIndex);
            if(totalDist < minTotalDist) {
                minTotalDist = totalDist;
                indexOfMin = curLocIndex;
            }
        }

        // Set toponymToDisambiguate's index to the index of the Location with the overall minimum distance
        // from above, if one was found
        if(indexOfMin >= 0) {
            toponymToDisambiguate.setSelectedIdx(indexOfMin);
        }
    }

  /* This is an alternative implementation of disambiguate that immediately
   * stops computing distance totals for candidates when it becomes clear
   * that they aren't minimal. */
  public StoredCorpus disambiguateAlt(StoredCorpus corpus) {
    for (Document<StoredToken> doc : corpus) {
      for (Sentence<StoredToken> sent : doc) {
        for (Token token : sent.getToponyms()) {
          Toponym toponym = (Toponym) token;
          double min = Double.MAX_VALUE;
          int minIdx = -1;

          int idx = 0;
          for (Location candidate : toponym) {
            Double candidateMin = this.checkCandidate(toponym, candidate, doc, min);
            if (candidateMin != null) {
              min = candidateMin;
              minIdx = idx;
            }
            idx++;
          }

          if (minIdx > -1) {
            toponym.setSelectedIdx(minIdx);
          }
        }
      }
    }

    return corpus;
  }

  /* Returns the minimum total distance to all other locations in the document
   * for the candidate, or null if it's greater than the current minimum. */
  public Double checkCandidate(Toponym toponym, Location candidate, Document<StoredToken> doc, double currentMinTotal) {
    Double total = 0.0;
    int seen = 0;

    for (Sentence<StoredToken> otherSent : doc) {
      for (Token otherToken : otherSent.getToponyms()) {
        Toponym otherToponym = (Toponym) otherToken;

        /* We don't want to compute distances if this other toponym is the
         * same as the current one, or if it has no candidates. */  
        if (!otherToponym.equals(toponym) && otherToponym.getAmbiguity() > 0) {
          double min = Double.MAX_VALUE;

          for (Location otherLoc : otherToponym) {
            double dist = candidate.distance(otherLoc);
            if (dist < min) {
              min = dist;
            }
          }

          seen++;
          total += min;

          /* If the running total is greater than the current minimum, we can
           * stop. */
          if (total >= currentMinTotal) {
            return null;
          }
        }
      }
    }

    /* Abstain if we haven't seen any other toponyms. */
    return seen > 0 ? total : null;
  }
}


package edu.virginia.psyc.pi.domain.RestExceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Created by dan on 10/26/15.
 */
@ResponseStatus(value= HttpStatus.NOT_FOUND, reason = "We have no questionnaire by that name.")
public class NoSuchQuestionnaireException extends RuntimeException {}

package edu.virginia.psyc.pi.controller;

import edu.virginia.psyc.mindtrails.domain.Participant;
import edu.virginia.psyc.mindtrails.domain.forms.ParticipantForm;
import edu.virginia.psyc.mindtrails.domain.recaptcha.RecaptchaFormValidator;
import edu.virginia.psyc.mindtrails.persistence.ParticipantRepository;
import edu.virginia.psyc.pi.domain.CBMStudy;
import edu.virginia.psyc.pi.domain.Dass21FromPi;
import edu.virginia.psyc.pi.domain.PiParticipant;
import edu.virginia.psyc.pi.persistence.PiParticipantRepository;
import edu.virginia.psyc.pi.persistence.Questionnaire.DASS21_AS;
import edu.virginia.psyc.pi.persistence.Questionnaire.DASS21_ASRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.security.Principal;
import java.util.Date;

// import jdk.internal.org.objectweb.asm.tree.analysis.Value;

/**
 * Created with IntelliJ IDEA.
 * User: dan
 * Date: 3/26/14
 * Time: 10:04 PM
 * To change this template use File | Settings | File Templates.
 */
@Controller
public class CreateAccountController {

    private static final Logger LOG = LoggerFactory.getLogger(CreateAccountController.class);

    // Eligibility form is saved to the session for retrieval when the user it found.
    private static final String DASS21_SESSION = "dass21";


    @Autowired
    private DASS21_ASRepository dass21_asRepository;

    @Value("${recaptcha.site-key}")
    private String recaptchaSiteKey;

    @Value("${tango.maxParticipants}")
    private long maxParticipantsForGiftCards;

    @Autowired
    private RecaptchaFormValidator recaptchaFormValidator;


    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private PiParticipantRepository piParticipantRepository;

    private PiParticipant getParticipant(Principal p) {
        return piParticipantRepository.findByEmail(p.getName());
    }


    @RequestMapping("public/eligibility")
    public String showEligibility(ModelMap model) {
        // Template will set a difference form action if this variable is set to true.
        model.addAttribute("eligibility",true);
        PiParticipant p = new PiParticipant();
        p.setTheme("blue");
        model.addAttribute("participant",p);

        return "questions/DASS21_AS";
    }

    @RequestMapping(value="public/eligibilityCheck", method = RequestMethod.POST)
    public String checkEligibility(@ModelAttribute("DASS21_AS") DASS21_AS dass21_as,
                                   ModelMap model,
                                   HttpSession session) {

        if(dass21_as.eligibleScore()) {
            // Save the DASS21_AS object in the session, so we can grab it when the
            // user is logged in.
            session.setAttribute("dass21", dass21_as);
            model.addAttribute("participant", new PiParticipant());
            return "invitation";
        } else {
            return "ineligible";
        }
    }

    // An external endpoint that bypasses the eligibility form, in the case
    // where the form is filled out on a remote site, and it's results are
    // added here.
    @RequestMapping(value="public/eligible", method = RequestMethod.POST)
    public String eligable(@ModelAttribute Dass21FromPi data,
                                   ModelMap model,
                                   HttpSession session) throws Exception {

        model.addAttribute("visiting", true);
        DASS21_AS dass21 = data.asDass21Object();
        return checkEligibility(dass21,model,session);
    }

    @RequestMapping("invitation")
    public String showInvitation(ModelMap model, Principal principal) {
        model.addAttribute("visiting", true);
        return "invitation";
    }

    /** This will assure that any form submissions for the participant Form
     * are validated for a proper recaptcha response.
     * @param binder
     */
    @InitBinder("participantForm")
    public void initBinder(WebDataBinder binder) {
        binder.addValidators(recaptchaFormValidator);
    }

    @RequestMapping(value="/consent", method = RequestMethod.GET)
    public String showConsent (ModelMap model, Principal principal) {
        model.addAttribute("participantForm", new ParticipantForm());
        model.addAttribute("visiting", true);
        model.addAttribute("recaptchaSiteKey", recaptchaSiteKey);
        return "consent";
    }


    @RequestMapping(value = "/newParticipant", method = RequestMethod.POST)
    public String createNewParticipant(ModelMap model,
                                       @ModelAttribute("participantForm") @Valid ParticipantForm participantForm,
                                       final BindingResult bindingResult,
                                       HttpSession session
                                       ) {

        PiParticipant participant;

        if(!participantForm.validParticipant(bindingResult, participantRepository)) {
            LOG.error("Invalid participant:" + bindingResult.getAllErrors());
            model.addAttribute("visiting", true);
            model.addAttribute("recaptchaSiteKey", recaptchaSiteKey);
            return "consent";
        }

        participant = new PiParticipant(participantForm.getFullName(),
                participantForm.getEmail(),
                participantForm.isAdmin());

        // Disable Gift Cards, if the max number is reached.
        long totalParticipants = participantRepository.count();
        participant.setReceiveGiftCards(maxParticipantsForGiftCards > totalParticipants);

        participant.updatePassword(participantForm.getPassword());
        if(participantForm.getTheme()!=null)
            participant.setTheme(participantForm.getTheme());
        participant.setOver18(participantForm.isOver18());
        participant.setLastLoginDate(new Date());

        piParticipantRepository.save(participant);

        // Log this new person in.
        Authentication auth = new UsernamePasswordAuthenticationToken( participantForm.getEmail(), participantForm.getPassword());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Save the Eligibility form
        saveEligibilityForm(participant, session);

        LOG.info("Participant authenticated.");
        return "redirect:/account/theme";
    }

    /**
     * Users have to complete an eligibility form to create an account, but you can't
     * save the form till the account exists.  So the results of the form are stored
     * in the session until the user creates an account, at which point
     * they are recorded to the database.
     * @param participant
     * @param session
     */
    private void saveEligibilityForm(Participant participant, HttpSession session) {
        DASS21_AS dass21_as;

        // Save their dass21 score to the Database
        dass21_as = (DASS21_AS)session.getAttribute(DASS21_SESSION);
        if(dass21_as == null) return;   // No eligiblity form exists in the session.
        dass21_as.setParticipant(participant);
        dass21_as.setDate(new Date());
        dass21_as.setSession(CBMStudy.NAME.ELIGIBLE.toString());
        dass21_asRepository.save(dass21_as);
    }



}
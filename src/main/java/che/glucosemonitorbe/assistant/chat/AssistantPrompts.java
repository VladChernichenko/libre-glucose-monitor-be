package che.glucosemonitorbe.assistant.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Prompts that hand the LLM a {@link FactsPayload}, and the plain templates used when a reply fails the
 * number guard twice. Templates print payload numbers rounded to one decimal, so they always pass the guard.
 */
final class AssistantPrompts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AssistantPrompts() {}

    static String question(FactsPayload facts, String question, List<String> offending) {
        return "You are a personal type 1 diabetes assistant chatting with the user. "
                + "Reply in " + languageName(facts.language())
                + ", the language of the user's message, even though the facts are in English. "
                + "Be brief: a few sentences, plain text. "
                + "Quote numbers, times and dates ONLY from the facts JSON below (converting mmol/L to mg/dL is "
                + "allowed); never invent, estimate or recompute numbers. 'citations' are clinical passages: you may "
                + "use them for background and cite one by writing its marker, e.g. [1] for ref 1, at the end of "
                + "the sentence. A number from a passage may appear only in a sentence that carries that passage's "
                + "marker, and never as an insulin dose in units. "
                + "Never issue imperative dosing commands; give cautious decision-support wording. "
                + "'state' is now; 'episodes' are the user's own past events with their outcomes "
                + "(minutes are counted from 'at'); refer to them when they help answer. "
                + "'plan' (if present) is the meal plan card shown earlier in this chat: 'doseUnits' is the "
                + "model's dose. 'history' (if present) is that card's history dose: 'historyUnits' comes from "
                + "the similar past meals in 'history.episodes' - for each, the insulin per gram it implies "
                + "(the dose taken, adjusted for where glucose landed at +4 h and for any low), the median of "
                + "those times this meal's carbs, plus today's correction minus IOB, rounded to 0.5 U. Use it "
                + "to explain why the two doses differ; neither is preferred. "
                + retry(offending)
                + "\nFacts JSON:\n" + json(facts)
                + "\nUser message:\n" + question;
    }

    static String plan(FactsPayload facts, List<String> offending) {
        return "You explain a meal plan to a person with type 1 diabetes. Reply in " + languageName(facts.language())
                + " in one to three short sentences, plain text. Use ONLY the numbers in the facts JSON below; "
                + "never invent or recompute numbers. Never issue imperative dosing commands; describe what the "
                + "model suggests. If fat or protein is high, say the rise may come late. "
                + "If the calculator refused or there is no fresh CGM reading, say so plainly. "
                + "'episodes' are the user's similar past meals with their outcomes; you may compare with them. "
                + retry(offending)
                + "\nFacts JSON:\n" + json(facts);
    }

    static String questionTemplate(FactsPayload f) {
        boolean ru = "ru".equals(f.language());
        FactsPayload.State s = f.state() == null ? FactsPayload.State.builder().build() : f.state();
        StringBuilder sb = new StringBuilder();
        if (s.glucose() != null) {
            sb.append(ru ? "Сейчас: глюкоза " : "Right now: glucose ").append(fmt(s.glucose()))
                    .append(ru ? " ммоль/л" : " mmol/L");
            if (s.glucoseAt() != null) sb.append(ru ? " в " : " at ").append(s.glucoseAt());
            sb.append(", ");
        } else {
            sb.append(ru ? "Свежих данных CGM сейчас нет. " : "No fresh CGM reading right now. ");
        }
        sb.append("IOB ").append(fmt(s.iob())).append(ru ? " ЕД" : " U")
                .append(", COB ").append(fmt(s.cob())).append(ru ? " г." : " g.");
        if (f.episodes() != null && !f.episodes().isEmpty()) {
            sb.append(ru ? " Похожие эпизоды из вашей истории показаны выше." : " Similar past episodes are shown above.");
        }
        FactsPayload.HistoryFacts h = f.history();
        if (h != null && h.historyUnits() != null && h.modelUnits() != null && h.episodeCount() != null) {
            sb.append(ru ? " Доза модели " : " The model's dose was ").append(fmt(h.modelUnits()))
                    .append(ru ? " ЕД; доза по истории " : " U; the history dose ").append(fmt(h.historyUnits()))
                    .append(ru ? " ЕД получена из " : " U comes from ").append(h.episodeCount())
                    .append(ru ? " похожих прошлых приёмов пищи." : " similar past meals.");
        }
        sb.append(ru ? " Надёжно ответить по вашим данным не получилось; проверьте детали в приложении."
                : " I could not answer this reliably from your data; please check the details in the app.");
        return sb.toString();
    }

    static String planTemplate(FactsPayload f) {
        boolean ru = "ru".equals(f.language());
        FactsPayload.PlanFacts p = f.plan();
        if (p == null) return questionTemplate(f);
        String carbs = (ru ? " Углеводы около " : " Carbs about ") + fmt(p.carbs()) + (ru ? " г." : " g.");
        if (Boolean.TRUE.equals(p.noFreshCgmReading())) {
            return (ru ? "Свежих данных CGM нет, поэтому доза и пауза не предлагаются."
                    : "No fresh CGM reading, so no dose or pause is suggested.") + carbs;
        }
        if (p.calculatorRefused() != null) {
            return (ru ? "Калькулятор не предложил дозу (" : "The calculator did not suggest a dose (")
                    + p.calculatorRefused() + ")." + carbs;
        }
        StringBuilder sb = new StringBuilder(ru ? "Модель предлагает " : "The model suggests ")
                .append(fmt(p.doseUnits())).append(ru ? " ЕД" : " U");
        if (p.pauseMinutes() != null) {
            sb.append(ru ? ", пауза " : ", ").append(p.pauseMinutes()).append(ru ? " мин до еды" : " min before eating");
        }
        if (p.forecastPeak() != null && p.forecastLowest() != null) {
            sb.append(ru ? "; прогноз: пик " : "; forecast peak ").append(fmt(p.forecastPeak()))
                    .append(ru ? ", минимум " : ", lowest ").append(fmt(p.forecastLowest()))
                    .append(ru ? " ммоль/л" : " mmol/L");
        }
        return sb.append('.').append(carbs).toString();
    }

    private static String retry(List<String> offending) {
        if (offending == null || offending.isEmpty()) return "";
        return "Your previous answer used numbers or dosing commands not in the facts: " + String.join(", ", offending)
                + ". Answer again without them, using only numbers from the facts and never telling the user "
                + "to take, inject or add insulin. ";
    }

    static String json(FactsPayload facts) {
        try {
            return JSON.writeValueAsString(facts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise the facts payload", e);
        }
    }

    private static String languageName(String language) {
        return "ru".equals(language) ? "Russian" : "English";
    }

    static String fmt(Double v) {
        if (v == null || !Double.isFinite(v)) return "?";
        double r = Math.round(v * 10.0) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}

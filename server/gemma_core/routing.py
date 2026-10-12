"""
What kind of message is this, decided cheaply and before any model call.

The route decides what a turn is allowed to cost. Ordinary conversation takes
the fast path (no document retrieval, no research planner, no classifier
calls); document questions go to the document system without the web planner;
everything else may consult the research planner as before.

Only Glen's CURRENT message chooses a structural target ("Episode 3"). Earlier
messages never do: a past mention of Episode 4 must not turn "what do you think
of the whole script?" into an Episode 4 lookup (the v9 failure).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

STRUCTURE_WORDS = ("episode", "chapter", "act", "scene", "part", "section", "page")
_NUM = r"(\d{1,3}|[ivx]{1,5}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
_STRUCTURE_RE = re.compile(rf"\b(episode|chapter|act|scene|part|section)\s+{_NUM}\b", re.I)
_WORD_NUMBERS = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
                 "nine": 9, "ten": 10, "eleven": 11, "twelve": 12}
_ROMAN = {"i": 1, "ii": 2, "iii": 3, "iv": 4, "v": 5, "vi": 6, "vii": 7, "viii": 8, "ix": 9, "x": 10,
          "xi": 11, "xii": 12, "xiii": 13, "xiv": 14, "xv": 15}

DOC_NOUNS = ("document", "doc", "script", "file", "attachment", "pdf", "story", "screenplay", "draft",
             "manuscript", "chapter", "episode", "scene", "the text", "report", "spreadsheet", "transcript")
_WHOLE_RE = re.compile(
    r"\b(whole|entire|overall|complete|full|all of (the|it)|as a whole|as a story|end to end|from start to finish|"
    r"in its entirety|start to finish|beginning to end|every episode|each episode|all (the )?episodes)\b", re.I)
_OPINION_RE = re.compile(
    r"\b(what do you think|what did you think|your (opinion|thoughts|take|view|verdict)|how (good|well) (is|does)|"
    r"critique|review|assess|evaluate|appraise|is it (any )?good|does it work|strengths|weaknesses|"
    r"how does the story work|what works|what doesn'?t work|feedback)\b", re.I)
_REQUEST_RE = re.compile(
    r"(\?|\b(summari[sz]e|summary|tell me|explain|describe|list|show|find|read|look at|have a look|analy[sz]e|"
    r"compare|review|critique|give me|write|what|who|where|when|why|how|which|can you|could you|would you|will you|"
    r"please|does|did|is there|are there|recap|outline|go through|walk me through|check|quote)\b)", re.I)
_FUTURE_UPLOAD_RE = re.compile(
    r"\b(let me|i'?ll|i will|i'?m going to|going to|gonna|about to|i can)\s+(upload|attach|send|share|give you|forward|drop)\b"
    r"|\b(want|like) (me )?to (upload|attach|send|share)\b", re.I)
_WEB_RE = re.compile(r"\b(search (the )?(web|internet|online)|look (it |that )?up|google|online|latest news|on the web|website)\b", re.I)
_GREETING_RE = re.compile(
    r"^\W*(good )?(morning|afternoon|evening|night|hi+|hey+|hello+|yo|g'?day|howdy|sup|hiya)\b", re.I)
_SMALLTALK_RE = re.compile(
    r"^\W*(thanks|thank you|cheers|ta|ok(ay)?|cool|nice|great|awesome|lol|haha+|hehe+|yes|yeah|yep|no|nope|"
    r"sure|alright|night|goodnight|bye|see you|love you|miss(ed)? you|sorry|wow|oh|aw+|ah+|hmm+|brilliant|perfect)\b", re.I)
_CONTINUE_RE = re.compile(r"\b(continue|keep|carry on|resume|finish|pick up where)\b.*\b(reading|processing|the document|the script|the file|where you (left off|stopped))\b", re.I)
_FOLLOWUP_RE = re.compile(r"^\W*(and|what about|how about|then|after that|what happens next|next one|the next)\b", re.I)


@dataclass
class Route:
    intent: str                      # CASUAL, CONVERSATIONAL, AWAITING_UPLOAD, STRUCTURAL_DETAIL,
                                     # WHOLE_DOCUMENT_ANALYSIS, DOCUMENT_QUESTION, RESUME_DOCUMENT, GENERAL
    confidence: float = 0.6
    target: tuple | None = None      # ("episode", 3)
    wants_opinion: bool = False
    wants_web: bool = False
    reasons: list = field(default_factory=list)

    @property
    def is_document(self) -> bool:
        return self.intent in ("STRUCTURAL_DETAIL", "WHOLE_DOCUMENT_ANALYSIS", "DOCUMENT_QUESTION", "RESUME_DOCUMENT")

    @property
    def fast(self) -> bool:
        """No planner, no classifier, no retrieval."""
        return self.intent in ("CASUAL", "CONVERSATIONAL", "AWAITING_UPLOAD")

    def as_dict(self) -> dict:
        return {"intent": self.intent, "confidence": self.confidence,
                "target": f"{self.target[0]} {self.target[1]}" if self.target else None,
                "wants_opinion": self.wants_opinion, "wants_web": self.wants_web, "reasons": self.reasons}


def number_token(value) -> int | None:
    raw = str(value or "").strip().lower()
    if raw.isdigit():
        return int(raw)
    return _WORD_NUMBERS.get(raw) or _ROMAN.get(raw)


def structure_target(text: str):
    """("episode", 3) for an explicit structural reference in this text, else None."""
    m = _STRUCTURE_RE.search(str(text or ""))
    if not m:
        return None
    n = number_token(m.group(2))
    return (m.group(1).lower(), n) if n is not None else None


def _mentions_document(q: str) -> bool:
    return any(re.search(rf"\b{re.escape(n)}s?\b", q) for n in DOC_NOUNS)


def classify(text: str, *, attachment_now: bool = False, documents_known: bool = False,
             previous: "Route | None" = None) -> Route:
    q = " ".join(str(text or "").lower().split())
    words = re.findall(r"[a-z0-9']+", q)
    is_request = bool(_REQUEST_RE.search(q))
    target = structure_target(q)
    mentions_doc = _mentions_document(q) or target is not None
    wants_web = bool(_WEB_RE.search(q))
    r = Route("GENERAL", 0.5, wants_web=wants_web)

    if not q:
        r.intent, r.reasons = "CASUAL", ["empty"]
        return r

    if _FUTURE_UPLOAD_RE.search(q) and not attachment_now:
        r.intent, r.confidence, r.reasons = "AWAITING_UPLOAD", 0.9, ["talks about sending a file", "nothing attached now"]
        return r

    if documents_known or attachment_now:
        if _CONTINUE_RE.search(q):
            r.intent, r.confidence, r.reasons = "RESUME_DOCUMENT", 0.85, ["asks to continue document processing"]
            return r
        whole = bool(_WHOLE_RE.search(q))
        opinion = bool(_OPINION_RE.search(q))
        if mentions_doc or attachment_now:
            if target and is_request and not whole:
                r.intent, r.target, r.confidence = "STRUCTURAL_DETAIL", target, 0.9
                r.wants_opinion = opinion
                r.reasons = [f"explicit {target[0]} {target[1]} in this message"]
                return r
            if (whole and (is_request or opinion)) or (opinion and not target):
                r.intent, r.confidence, r.wants_opinion = "WHOLE_DOCUMENT_ANALYSIS", 0.85, opinion
                r.target = target
                r.reasons = ["whole-document wording" if whole else "asks for an opinion of the document"]
                return r
            if is_request:
                r.intent, r.confidence, r.wants_opinion = "DOCUMENT_QUESTION", 0.7, opinion
                r.reasons = ["document-related request without a structural target"]
                return r
            if attachment_now:
                r.intent, r.confidence = "DOCUMENT_QUESTION", 0.6
                r.reasons = ["file attached with this message"]
                return r
            # Talks about the document but asks nothing ("you should now have the whole script").
            r.intent, r.confidence, r.reasons = "CONVERSATIONAL", 0.75, ["mentions a document but makes no request"]
            return r
        if previous is not None and previous.is_document and _FOLLOWUP_RE.search(q) and is_request and len(words) <= 12:
            r.intent, r.confidence, r.reasons = "DOCUMENT_QUESTION", 0.6, ["short follow-up to a document question"]
            return r

    if not is_request and len(words) <= 14 and (_GREETING_RE.search(q) or _SMALLTALK_RE.search(q)):
        r.intent, r.confidence, r.reasons = "CASUAL", 0.9, ["greeting or small talk"]
        return r
    if not is_request and not wants_web and len(words) <= 40 and not re.search(r"\d", q):
        r.intent, r.confidence, r.reasons = "CONVERSATIONAL", 0.65, ["a statement, not a question or request"]
        return r
    r.reasons = ["question or request; the research planner decides whether to look anything up"]
    return r

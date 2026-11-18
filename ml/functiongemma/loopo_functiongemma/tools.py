"""FunctionGemma schema and validation matching Loopo's current voice plan."""

from typing import Any, TypedDict


class Call(TypedDict):
    name: str
    arguments: dict[str, int]


NO_CALL_TEXT = "Comando non riconosciuto."
DEVELOPER_PROMPT = (
    "You are a model that can do function calling with the following functions. "
    "Interpreta un comando vocale italiano per Loopo. Usa soltanto gli strumenti dichiarati. "
    "È ammessa una sola azione oppure un cambio di velocità seguito da riprendi o ricomincia. "
    f"Per richieste ambigue, negate o non supportate, non chiamare strumenti e rispondi: {NO_CALL_TEXT}"
)

# name -> description, argument, argument description, inclusive bounds
_DEFINITIONS: dict[str, tuple[str, str | None, str | None, int | None, int | None]] = {
    "resume": ("Riprendi la riproduzione dal punto corrente.", None, None, None, None),
    "restart": ("Ricomincia la riproduzione dall'inizio del loop o del brano.", None, None, None, None),
    "stay_paused": ("Lascia la riproduzione in pausa.", None, None, None, None),
    "set_rate": (
        "Imposta la velocità assoluta di riproduzione senza avviare la traccia.",
        "percent", "Velocità intera in percentuale da 25 a 200.", 25, 200,
    ),
    "adjust_rate": (
        "Aumenta o riduce la velocità corrente senza avviare la traccia.",
        "delta_percent", "Variazione intera in punti percentuali da -175 a 175, escluso zero.", -175, 175,
    ),
}


def _tool(name: str) -> dict[str, Any]:
    description, argument, argument_description, lower, upper = _DEFINITIONS[name]
    properties: dict[str, Any] = {}
    if argument is not None:
        properties[argument] = {
            "type": "integer",
            "description": argument_description,
            "minimum": lower,
            "maximum": upper,
        }
        if name == "adjust_rate":
            properties[argument]["not"] = {"const": 0}
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": list(properties),
                "additionalProperties": False,
            },
        },
    }


TOOLS = [_tool(name) for name in _DEFINITIONS]


def validate_plan(calls: object) -> list[Call]:
    """Return a checked plan or raise ValueError when it is outside app behavior."""
    if not isinstance(calls, list) or len(calls) not in (0, 1, 2):
        raise ValueError("expected zero, one, or two ordered calls")
    checked: list[Call] = []
    for call in calls:
        if not isinstance(call, dict) or set(call) != {"name", "arguments"}:
            raise ValueError("each call needs exactly a name and arguments")
        name = call["name"]
        if not isinstance(name, str) or name not in _DEFINITIONS:
            raise ValueError(f"unknown tool {name!r}")
        _, argument, _, lower, upper = _DEFINITIONS[name]
        arguments = call["arguments"]
        expected_keys = {argument} if argument is not None else set()
        if not isinstance(arguments, dict) or set(arguments) != expected_keys:
            raise ValueError(f"wrong arguments for {name}")
        if argument is not None:
            value = arguments[argument]
            if type(value) is not int or not lower <= value <= upper:
                raise ValueError(f"invalid value for {name}.{argument}")
            if name == "adjust_rate" and value == 0:
                raise ValueError("adjust_rate.delta_percent cannot be zero")
        checked.append({"name": name, "arguments": dict(arguments)})
    if len(checked) == 2 and (
        checked[0]["name"] not in {"set_rate", "adjust_rate"}
        or checked[1]["name"] not in {"resume", "restart"}
    ):
        raise ValueError("a compound plan must change rate then resume or restart")
    return checked


def to_conversation(row: dict[str, Any]) -> dict[str, Any]:
    """Convert a dataset row to native messages plus FunctionGemma tool schemas."""
    calls = validate_plan(row["expected"])
    assistant: dict[str, Any] = {"role": "assistant"}
    if calls:
        assistant["tool_calls"] = [
            {"type": "function", "function": call} for call in calls
        ]
    else:
        assistant["content"] = NO_CALL_TEXT
    return {
        "messages": [
            {"role": "developer", "content": DEVELOPER_PROMPT},
            {"role": "user", "content": row["text"]},
            assistant,
        ],
        "tools": TOOLS,
    }

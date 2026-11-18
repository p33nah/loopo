"""Render native conversations and validate the entire generated FunctionGemma turn."""

import re
from typing import Any

from .tools import Call, to_conversation, validate_plan

CALL_START = "<start_function_call>"
TOOL_RESPONSE_START = "<start_function_response>"
TURN_END = "<end_of_turn>"
STOPS = (TOOL_RESPONSE_START, TURN_END)
CALL = re.compile(r"<start_function_call>call:([a-z_]+)\{([^{}]*)\}<end_function_call>")
ARGUMENT = re.compile(r"([a-z_]+):\s*(-?[0-9]{1,3})")


def parse_native(output: str) -> list[Call]:
    """Parse one completed native turn. Free text without control tokens rejects the request."""
    body = output.strip()
    stop = next((token for token in STOPS if body.endswith(token)), None)
    if not stop:
        raise ValueError("the model did not end its turn")
    body = body.removesuffix(stop).strip()
    if not body.startswith(CALL_START):
        if stop != TURN_END or not body or re.search(r"<[a-z_]+>", body):
            raise ValueError("invalid no-call response")
        return []

    calls = []
    while body:
        match = CALL.match(body)
        if not match or len(calls) >= 2:
            raise ValueError("malformed or extra tool call")
        name, arguments = match.groups()
        values = {}
        if arguments.strip():
            argument = ARGUMENT.fullmatch(arguments.strip())
            if not argument:
                raise ValueError("expected one integer argument")
            key, value = argument.groups()
            values[key] = int(value)
        calls.append({"name": name, "arguments": values})
        body = body[match.end():].lstrip()
    return validate_plan(calls)


def render_prompt(tokenizer: Any, text: str) -> Any:
    conversation = to_conversation({"text": text, "expected": []})
    return tokenizer.apply_chat_template(
        conversation["messages"][:-1], tools=conversation["tools"],
        add_generation_prompt=True, tokenize=True, return_dict=True,
    )


def training_features(tokenizer: Any, row: dict, max_length: int) -> dict:
    conversation = to_conversation(row)
    prompt = render_prompt(tokenizer, row["text"])["input_ids"]
    tokens = tokenizer.apply_chat_template(
        conversation["messages"], tools=conversation["tools"],
        tokenize=True, return_dict=True,
    )["input_ids"]
    if tokens[:len(prompt)] != prompt or len(tokens) <= len(prompt):
        raise ValueError(f"{row['id']}: inference prompt is not a prefix of the training turn")
    if len(tokens) > max_length:
        raise ValueError(f"{row['id']}: {len(tokens)} tokens exceed max_length={max_length}")
    completion = tokenizer.decode(
        tokens[len(prompt):], skip_special_tokens=False, clean_up_tokenization_spaces=False,
    )
    if parse_native(completion) != row["expected"]:
        raise ValueError(f"{row['id']}: native template round-trip changed the expected plan")
    return {"input_ids": tokens, "completion_mask": [0] * len(prompt) + [1] * (len(tokens) - len(prompt))}

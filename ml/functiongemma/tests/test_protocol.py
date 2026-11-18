from pathlib import Path

import pytest

from loopo_functiongemma.protocol import parse_native


SET_RATE = "<start_function_call>call:set_rate{percent:72}<end_function_call>"
ADJUST = "<start_function_call>call:adjust_rate{delta_percent:-5}<end_function_call>"
RESTART = "<start_function_call>call:restart{}<end_function_call>"
STOP = "<start_function_response>"


def test_native_compound_keeps_order_and_integer_values():
    assert parse_native(ADJUST + RESTART + STOP) == [
        {"name": "adjust_rate", "arguments": {"delta_percent": -5}},
        {"name": "restart", "arguments": {}},
    ]
    assert parse_native("Comando non riconosciuto.<end_of_turn>") == []


@pytest.mark.parametrize("response", [
    SET_RATE, SET_RATE + STOP + "unexpected",
    "Forse " + SET_RATE + STOP, SET_RATE + RESTART + RESTART + STOP,
    RESTART + SET_RATE + STOP, "<start_function_call>call:seek{}<end_function_call>" + STOP,
    SET_RATE.replace("72", "20") + STOP, SET_RATE.replace("72", "72.0") + STOP,
    SET_RATE.replace("72", "<escape>72<escape>") + STOP,
    SET_RATE.replace("72", "72,percent:73") + STOP,
    SET_RATE.replace("<end_function_call>", "") + STOP,
    "<start_function_call>call:adjust_rate{delta_percent:0}<end_function_call>" + STOP,
])
def test_invalid_turn_is_rejected_as_a_whole(response):
    with pytest.raises(ValueError):
        parse_native(response)


def test_colab_notebook_source_compiles():
    import nbformat

    path = Path(__file__).resolve().parents[1] / "notebooks/loopo_functiongemma_finetuning.ipynb"
    notebook = nbformat.reads(path.read_text(encoding="utf-8"), as_version=4)
    nbformat.validate(notebook)
    for cell in notebook.cells:
        if cell.cell_type == "code":
            compile(cell.source, f"Colab cell {cell.id}", "exec")
            assert not cell.outputs

import tomllib
from pathlib import Path

import pytest

from loopo_functiongemma.train import make_trainer


def test_cpu_training_masks_prompt_saves_and_reloads_adapter(tmp_path, monkeypatch):
    torch = pytest.importorskip("torch")
    pytest.importorskip("trl")
    pytest.importorskip("peft")
    from peft import PeftModel
    from tokenizers import Tokenizer
    from tokenizers.models import WordLevel
    from tokenizers.pre_tokenizers import WhitespaceSplit
    from transformers import Gemma3ForCausalLM, Gemma3TextConfig, PreTrainedTokenizerFast, TrainerCallback

    monkeypatch.setattr(torch.cuda, "is_available", lambda: False)
    torch.set_num_threads(1)
    torch.manual_seed(67)
    call = "<start_function_call>call:resume{}<end_function_call>"
    words = ["<pad>", "<unk>", "prompt", "yes", "no", call, "<start_function_response>", "<end_of_turn>"]
    backend = Tokenizer(WordLevel({word: i for i, word in enumerate(words)}, unk_token="<unk>"))
    backend.pre_tokenizer = WhitespaceSplit()
    tokenizer = PreTrainedTokenizerFast(tokenizer_object=backend, pad_token="<pad>", unk_token="<unk>")
    # A local trainer fixture. The real chat template is checked by model validate-format.
    tokenizer.chat_template = (
        "prompt {% if messages[-1].role == 'assistant' %}"
        "{% if messages[-1].tool_calls %}{{ '" + call + "' }} <start_function_response>"
        "{% else %}no <end_of_turn>{% endif %}{% endif %}"
    )
    config = Gemma3TextConfig(
        vocab_size=len(words), hidden_size=32, intermediate_size=64, num_hidden_layers=1,
        num_attention_heads=2, num_key_value_heads=2, head_dim=16,
        pad_token_id=0, bos_token_id=2, eos_token_id=7, use_cache=False,
    )
    model = Gemma3ForCausalLM(config)
    rows = [
        {"id": "play", "text": "Riprendi", "expected": [{"name": "resume", "arguments": {}}]},
        {"id": "reject", "text": "Qual è la temperatura?", "expected": []},
    ]
    settings = tomllib.loads((Path(__file__).resolve().parents[1] / "configs/smoke.toml").read_text())
    trainer = make_trainer(model, tokenizer, rows, rows, settings, tmp_path)
    batch = next(iter(trainer.get_train_dataloader()))
    assert batch["labels"][0, 0].item() == -100
    assert (batch["labels"][0, 1:] != -100).all()
    class InterruptAfterOneStep(TrainerCallback):
        def on_step_end(self, args, state, control, **kwargs):
            if state.global_step == 1:
                control.should_save = True
                control.should_training_stop = True

    trainer.add_callback(InterruptAfterOneStep())
    assert trainer.train().global_step == 1
    checkpoint = tmp_path / "checkpoint-1"
    assert (checkpoint / "optimizer.pt").is_file()
    trainer = make_trainer(Gemma3ForCausalLM(config), tokenizer, rows, rows, settings, tmp_path)
    result = trainer.train(resume_from_checkpoint=str(checkpoint))
    assert result.global_step == 2
    assert (tmp_path / "checkpoint-2" / "optimizer.pt").is_file()
    adapter = tmp_path / "adapter"
    trainer.save_model(adapter)
    reloaded = PeftModel.from_pretrained(Gemma3ForCausalLM(config), adapter)
    assert "default" in reloaded.peft_config

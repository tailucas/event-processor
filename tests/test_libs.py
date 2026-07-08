def test_app_imports(monkeypatch):


    pass

def test_permit_client():
    from permit.sync import Permit
    _ = Permit(pdp="hostname", token="permit_token")
